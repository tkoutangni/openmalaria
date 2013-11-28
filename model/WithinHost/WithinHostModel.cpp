/* This file is part of OpenMalaria.
 * 
 * Copyright (C) 2005-2013 Swiss Tropical and Public Health Institute 
 * Copyright (C) 2005-2013 Liverpool School Of Tropical Medicine
 * 
 * OpenMalaria is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or (at
 * your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */

#include "WithinHost/WithinHostModel.h"
#include "WithinHost/DescriptiveWithinHost.h"
#include "WithinHost/DescriptiveIPTWithinHost.h"
#include "WithinHost/ProphylacticActionWithinHost.h"
#include "WithinHost/CommonWithinHost.h"
#include "WithinHost/Infection/DummyInfection.h"
#include "WithinHost/Infection/EmpiricalInfection.h"
#include "WithinHost/Infection/MolineauxInfection.h"
#include "WithinHost/Infection/PennyInfection.h"
#include "util/random.h"
#include "util/ModelOptions.h"
#include "util/errors.h"
#include "schema/scenario.h"

#include <cmath>
#include <boost/format.hpp>


namespace OM { namespace WithinHost {

using namespace OM::util;

double WithinHostModel::sigma_i;
double WithinHostModel::immPenalty_22;
double WithinHostModel::asexImmRemain;
double WithinHostModel::immEffectorRemain;
double WithinHostModel::detectionLimit;

// -----  static functions  -----

void WithinHostModel::init( const OM::Parameters& parameters, const scnXml::Scenario& scenario ){
  Infection::init( parameters, scenario.getModel().getParameters().getLatentp() );
  sigma_i=sqrt(parameters[Parameters::SIGMA_I_SQ]);
  immPenalty_22=1-exp(parameters[Parameters::IMMUNITY_PENALTY]);
  immEffectorRemain=exp(-parameters[Parameters::IMMUNE_EFFECTOR_DECAY]);
  asexImmRemain=exp(-parameters[Parameters::ASEXUAL_IMMUNITY_DECAY]);
  
  double densitybias;
  if (util::ModelOptions::option (util::GARKI_DENSITY_BIAS)) {
      densitybias=parameters[Parameters::DENSITY_BIAS_GARKI];
  } else {
    int analysisNo = scenario.getAnalysisNo();
    if ((analysisNo >= 22) && (analysisNo <= 30)) {
	cerr << "Warning: these analysis numbers used to mean use Garki density bias. If you do want to use this, specify the option GARKI_DENSITY_BIAS; if not, nothing's wrong." << endl;
    }
    densitybias=parameters[Parameters::DENSITY_BIAS_NON_GARKI];
  }
  detectionLimit=scenario.getMonitoring().getSurveys().getDetectionLimit()*densitybias;
  
  if (util::ModelOptions::option (util::DUMMY_WITHIN_HOST_MODEL)) {
    DummyInfection::init ();
  } else if (util::ModelOptions::option (util::EMPIRICAL_WITHIN_HOST_MODEL)) {
    EmpiricalInfection::init();	// 1-day timestep check
  } else if (util::ModelOptions::option (util::MOLINEAUX_WITHIN_HOST_MODEL)) {
    MolineauxInfection::init( parameters );
  } else if (util::ModelOptions::option (util::PENNY_WITHIN_HOST_MODEL)) {
      PennyInfection::init();
  } else {
    DescriptiveInfection::init( parameters );	// 5-day timestep check
  }
}

WithinHostModel* WithinHostModel::createWithinHostModel () {
  if (util::ModelOptions::option (util::DUMMY_WITHIN_HOST_MODEL) ||
      util::ModelOptions::option (util::EMPIRICAL_WITHIN_HOST_MODEL) ||
      util::ModelOptions::option (util::MOLINEAUX_WITHIN_HOST_MODEL) ||
      util::ModelOptions::option (util::PENNY_WITHIN_HOST_MODEL)) {
    return new CommonWithinHost();
  } else {
    if( util::ModelOptions::option( IPTI_SP_MODEL ) )
      throw util::xml_scenario_error( "The IPT model is no longer available. Use MDA instead." );
    else if( util::ModelOptions::option( PROPHYLACTIC_DRUG_ACTION_MODEL ) )
        return new ProphylacticActionWithinHost();
    else
      return new DescriptiveWithinHostModel();
  }
}


// -----  Non-static  -----

WithinHostModel::WithinHostModel () :
    _cumulativeh(0.0), _cumulativeY(0.0), _cumulativeYlag(0.0),
    numInfs(0), totalDensity(0.0), timeStepMaxDensity(0.0)
{
    _innateImmSurvFact = exp(-random::gauss(0, sigma_i));
}


// -----  immunity  -----

void WithinHostModel::updateImmuneStatus(){
  if (immEffectorRemain < 1){
    _cumulativeh*=immEffectorRemain;
    _cumulativeY*=immEffectorRemain;
  }
  if (asexImmRemain < 1){
    _cumulativeh*=asexImmRemain/
        (1+(_cumulativeh*(1-asexImmRemain)/Infection::cumulativeHstar));
    _cumulativeY*=asexImmRemain/
        (1+(_cumulativeY*(1-asexImmRemain)/Infection::cumulativeYstar));
  }
  _cumulativeYlag = _cumulativeY;
}

void WithinHostModel::immunityPenalisation() {
  _cumulativeY = _cumulativeYlag - immPenalty_22*(_cumulativeY-_cumulativeYlag);
  if (_cumulativeY < 0) {
    _cumulativeY=0.0;
  }
}


// -----  Summarize  -----

bool WithinHostModel::summarize (Monitoring::Survey& survey, Monitoring::AgeGroup ageGroup) {
  int patentInfections = 0;
  int numInfections = countInfections (patentInfections);
  if (numInfections > 0) {
    survey.reportInfectedHosts(ageGroup,1);
    survey.addToInfections(ageGroup, numInfections);
    survey.addToPatentInfections(ageGroup, patentInfections);
  }
  // Treatments in the old ImmediateOutcomes clinical model clear infections immediately
  // (and are applied after update()); here we report the last calculated density.
  if (parasiteDensityDetectible()) {
    survey.reportPatentHosts(ageGroup, 1);
    survey.addToLogDensity(ageGroup, log(totalDensity));
    return true;
  }
  return false;
}


void WithinHostModel::checkpoint (istream& stream) {
    _innateImmSurvFact & stream;
    _cumulativeh & stream;
    _cumulativeY & stream;
    _cumulativeYlag & stream;
    numInfs & stream;
    totalDensity & stream;
    timeStepMaxDensity & stream;
    
    if (numInfs > MAX_INFECTIONS)
	throw util::checkpoint_error( (boost::format("numInfs: %1%") %numInfs).str() );
}
void WithinHostModel::checkpoint (ostream& stream) {
    _innateImmSurvFact & stream;
    _cumulativeh & stream;
    _cumulativeY & stream;
    _cumulativeYlag & stream;
    numInfs & stream;
    totalDensity & stream;
    timeStepMaxDensity & stream;
}

} }

/* This file is part of OpenMalaria.
 *
 * Copyright (C) 2005-2011 Swiss Tropical Institute and Liverpool School Of Tropical Medicine
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

import org.w3c.dom.*;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.*;
import java.lang.reflect.Method;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;


public class SchemaTranslator {

    DocumentBuilder _builder;
    Document scenarioDocument;
    Element scenarioElement;

    static final int CURRENT_VERSION = 31;

    private static int _required_version = CURRENT_VERSION;
    private enum SchemaName {
        versioned, no_suffix, current;
    }
    private static SchemaName latestSchema = SchemaName.versioned;
    private static boolean doValidation = true;
    private static boolean doTranslation = true;
    private static boolean doODTTranslation = false;
    private static boolean doDBUpdate = false;
    private static String schema_folder = "../../schema/";

    private enum BugCorrectionBehaviour {
        none, correct, dontCorrect;
    }
    private static BugCorrectionBehaviour maxDensBug = BugCorrectionBehaviour.none;

    private enum IptiSpBehaviour {
        none, assumeIntended, assumeUnintented;
    }
    private static IptiSpBehaviour iptiSpOption = IptiSpBehaviour.none;

    private enum IptiReportOnlyAtRiskBehaviour {
        none, on, off;
    }
    private static IptiReportOnlyAtRiskBehaviour iptiROAR = IptiReportOnlyAtRiskBehaviour.none;
    
    private enum ITN29ParameterTranslation {
        none, replace, manual;  // note: could add option to approximate old behaviour
    }
    private static ITN29ParameterTranslation ITN29Translation = ITN29ParameterTranslation.none;

    public static double HumanBloodIndex_NONNHS = 1;

    public static int INDEX_GAMBIAE_SS = 0;
    public static int INDEX_FUNESTUS = 1;
    public static int INDEX_ARABIENSIS = 2;

    public static String Name_GAMBIAE_SS = "gambiae_ss";
    public static String Name_FUNESTUS = "funestus";
    public static String Name_ARABIENSIS = "arabiensis";

    public static double[] HumanBloodIndexes = { 0.939, 0.98, 0.871 };
    public static double[] ProporitionsLaidEggsSameDay = { 0.313, 0.616, 0.313 };
    public static double[] PsSurvivalFeedingCycle = { 0.623, 0.611, 0.623 };
    public static double[] PAs = { 0.687, 0.384, 0.687 };
    public static double[] PA2s = { 0.0151, 0.00957, 0.320 };

    public static double td = 0.33;

    public static double Standard_RELATIVE_ENTO_AV = 1.0;
    public static double Standard_NHH_NUMBER = 1.0;
    
    static class DocException extends Exception{
        public DocException( String msg ){
            super(msg);
        }
    }

    /** Returns all direct children of node with name name.
     *
     * Note: not the same as getElementsByTagName(), which finds all descendants. */
    public static List<Node> getChildNodes( Node node, String name ) {
        NodeList children = node.getChildNodes();
        int l = children.getLength();
        ArrayList<Node> r = new ArrayList<Node>();
        for ( int i = 0; i < l; ++i ) {
            if ( name.equals( children.item(i).getNodeName() ) )
                r.add( children.item(i) );
        }
        return r;
    }
    /** Wrapper around getChildNodes() to get one Element.
     *
     * As a compromise between compatibility and safety, this will throw if
     * there is more one sub-element with given name, but return null if there
     * are no elements with the given name. */
    public static Element getChildElement( Node node, String name )throws DocException {
        List<Node> elts = getChildNodes( node, name );
        if ( elts.size() > 1 )
            throw new DocException( "Expected "+node.getNodeName()+" not to have more than one sub-element with name "+name );
        else if ( elts.size() == 1 )
            return (Element) elts.get(0);
        else
            return null;
    }
    /** Get or create a child element.
     *
     * If parent has a single existing element by name name, return that.
     * If it has none, create one and append it, then return it. Multiple
     * existing children by this name are not expected. */
    private Element getOrCreateSubElt(Element parent, String name)throws DocException {
        Element child = getChildElement(parent,name);
        if (child==null) {
            child = scenarioDocument.createElement(name);
            parent.appendChild(child);
        }
        return child;
    }
    /** Check whether scenario uses a particular model option. */
    public boolean usesOption( String name )throws DocException {
        Element opts = (Element) scenarioElement.getElementsByTagName("ModelOptions").item(0);
        assert opts != null;
        for ( Node n : getChildNodes(opts,"option") ) {
            Element e = (Element) n;
            assert e != null;
            if ( e.getAttribute("name").equals(name) ) {
                return Boolean.parseBoolean( e.getAttribute("value") );
            }
        }
        // No match: doesn't occur
        if ( name.equals( "MAX_DENS_CORRECTION" ) )
            return true;        // on by default
        else
            return false;       // all other options are off by default
    }

    public SchemaTranslator() {
        try {
            _builder = DocumentBuilderFactory.newInstance()
                       .newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            e.printStackTrace();
        }
    }

    static class STErrorHandler implements ErrorHandler {
        public void fatalError( SAXParseException e )
            throws SAXException {
            System.err.println("Error: "+e.toString());
            throw e;
        }
        public void error( SAXParseException e ){
            System.out.println("Error: "+e.toString());
        }
        public void warning( SAXParseException e ) throws SAXException {
            System.out.println("Error: "+e.toString());
        }
    }

    private void validate(Document scenarioDocument, String schemaFileName,
                          String schemaDirectory) throws Exception {
        Document forValidation = (Document) scenarioDocument.cloneNode(true);
        Element scenarioElement = forValidation.getDocumentElement();
        scenarioElement.removeAttribute("xsi:noNamespaceSchemaLocation");

        if ( _required_version <= 23 ) {
            // This is set by generateRun, but removed in schema 24
            scenarioElement.setAttribute("assimMode", "0");
        }
        // This is set by the work generator
        scenarioElement.setAttribute("wuID", "123");

        Element model = getChildElement(scenarioElement, "model");
        Element t_parameters = getChildElement(model, "parameters");

        if (t_parameters != null && t_parameters.getNodeValue() != null
                && t_parameters.getNodeValue().contains("@parameters@")) {
            t_parameters.getLastChild().setNodeValue("");
            // Add a dummy parameter
            Element parameters = forValidation.createElement("parameters");

            parameters.setAttribute("latentp", "0");
            parameters.setAttribute("delta", "0");
            parameters.setAttribute("interval", "0");
            parameters.setAttribute("iseed", "0");
            Element parameter = forValidation.createElement("parameter");
            parameter.setAttribute("value", "0");
            parameter.setAttribute("name", "0");
            parameter.setAttribute("number", "0");
            parameters.appendChild(parameter);
            scenarioElement.appendChild(parameters);
        }

        // Validate the updated document
        File xsdFile = new File(schemaDirectory + schemaFileName);
        if (xsdFile == null || !xsdFile.isFile()) {
            System.out.println("Unable to find " + schemaDirectory + schemaFileName
                               + " file; not validating.");
            return;
        }
        //System.out.println("Validating against " + schemaDirectory + schemaFileName);
        SchemaFactory factory = SchemaFactory
                                .newInstance("http://www.w3.org/2001/XMLSchema");
        // NOTE: this may throw a java.lang.NullPointerException when run within
        // eclipse
        // run from a command-line instead (java SchemaTranslator).
        Schema schema = factory.newSchema(xsdFile);
        Validator validator = schema.newValidator();
        validator.setErrorHandler(new STErrorHandler());
        Source source = new DOMSource(forValidation);
        try{
            validator.validate(source);
        }catch(SAXParseException e){
            // error already reported, so don't need to do anything
        }
    }

    // Helper function to strip old white-space.
    // Source: http://forums.sun.com/thread.jspa?threadID=5201482
    public static void visit(Node node, short nodeType, String name) {
        NodeList list = node.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            // Get child node
            Node childNode = list.item(i);
            if (childNode.getNodeType() == nodeType &&
                    (name == null || childNode.getNodeName().trim().equals(name) && childNode.getNodeValue().trim().equals(""))
               ) {
                childNode.getParentNode().removeChild(childNode);
                // child was removed so list invalid; easiest is to start again:
                visit(node, nodeType, name);
                break;
            }
            else {
                visit(childNode, nodeType, name);
            }
        }
    }
    private void translateFile(File documentFile, File outDir) throws Exception {
        scenarioDocument = _builder.parse(documentFile);
        String schemaFileName = translateDocument();
        if (schemaFileName == null) {
            System.err.println("Update of " + documentFile + " failed.");
            return;
        }
        if (doTranslation||doODTTranslation) {
            File outFile = new File(outDir, documentFile.getName());
            outFile.createNewFile();
            OutputStream os = new FileOutputStream(outFile);
            Result result = new StreamResult(os);
            // Write the DOM document to the file
            Transformer xformer = TransformerFactory.newInstance()
                                  .newTransformer();
            xformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            xformer.setOutputProperty(OutputKeys.METHOD, "xml");

            // Reformat. First, remove all whitespace nodes, then insert new whitespace.
            visit(scenarioElement, org.w3c.dom.Node.TEXT_NODE, "#text");
            scenarioDocument.normalize();
            // Then add new indentation/new-lines:
            xformer.setOutputProperty(OutputKeys.INDENT, "yes");
            xformer.setOutputProperty(
                "{http://xml.apache.org/xslt}indent-amount", "2");

            xformer.transform(new DOMSource(scenarioDocument), result);
        }
        if (doValidation)
            validate(scenarioDocument, schemaFileName, schema_folder);
    }

    private void updateDB() {
        Connection con = null;
        try {
            Class.forName("com.mysql.jdbc.Driver").newInstance();

            con = DriverManager.getConnection(
                      "jdbc:mysql://127.0.0.1:3306/DBNAME", "USER", "PASSWD");

            if (!con.isClosed())
                System.out.println("Successfully connected to "
                                   + "MySQL server using TCP/IP...");
            Statement stmt = con.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                                                 ResultSet.CONCUR_UPDATABLE);
            ResultSet uprs = stmt
                             .executeQuery("SELECT id,run_id,xml FROM scenarios");
            while (uprs.next()) {
                String xml = uprs.getString("xml");
                InputSource is = new InputSource();
                is.setCharacterStream(new StringReader(xml));
                _builder = DocumentBuilderFactory.newInstance()
                           .newDocumentBuilder();
                scenarioDocument = _builder.parse(is);
                String schemaFileName = translateDocument();
                Transformer transformer = TransformerFactory.newInstance()
                                          .newTransformer();
                transformer.setOutputProperty(OutputKeys.INDENT, "yes");
                StreamResult result = new StreamResult(new StringWriter());
                DOMSource source = new DOMSource(scenarioDocument);
                transformer.transform(source, result);
                String translatedXML = result.getWriter().toString();
                uprs.updateString("xml", translatedXML);
                if (doValidation)
                    validate(scenarioDocument, schemaFileName, "./");
                uprs.updateRow();
            }
        } catch (SQLException e) {

            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (con != null)
                    con.close();
            } catch (SQLException e) {
            }
        }

    }

    private static String genSchemaName (int schemaVersion) {
        if (latestSchema == SchemaName.no_suffix) {
            return "scenario.xsd";
        } else if (latestSchema == SchemaName.current) {
            return "scenario_current.xsd";
        } else {
            return "scenario_" + schemaVersion + ".xsd";
        }
    }

    private String translateDocument() throws Exception {
        scenarioElement = scenarioDocument.getDocumentElement();
        // System.out.println("Updating: " +
        // scenarioElement.getAttribute("name"));
        // 0 if no current version (getAttribute returns ""):
        int schemaVersion = Integer.parseInt("0"
                                             + scenarioElement.getAttribute("schemaVersion"));
        String schemaFileName = genSchemaName (schemaVersion);
        Class<? extends SchemaTranslator> cls = this.getClass();

        try {
            while (schemaVersion < _required_version) {
                ++schemaVersion;
                schemaFileName = genSchemaName (schemaVersion);
                scenarioElement.setAttribute("schemaVersion", Integer
                                             .toString(schemaVersion));
                scenarioElement.setAttribute("xsi:noNamespaceSchemaLocation",
                                             schemaFileName);

                String translateMeth = "translate" + (schemaVersion - 1) + "To"
                                       + schemaVersion;
                Method method = cls.getMethod(translateMeth, new Class[] {});
                if (method == null)
                    throw new Exception("Method " + translateMeth + " not found");
                // Call the function. Note that while we catch exceptions thrown
                // by Method, we don't appear able to catch exceptions thrown
                // by the function called, instead interpret false as failure.
                if (!(Boolean) method.invoke(this, new Object[] {}))
                    return null;
            }

            if (schemaVersion == 18 && doODTTranslation) {
                if (!oDTTranslation())
                    return null;
            }
        } catch (java.lang.reflect.InvocationTargetException ite) {
            System.err.println("Error:");
            ite.getCause().printStackTrace();
            return null;
        }

        return schemaFileName;
    }


    // Version 0 refers to before schema versioning was started.
    // Exactly what version 1 is has been forgotten; it's merged into 2.
    public Boolean translate0To1() {
        return true;
    }

    public Boolean translate1To2() throws Exception {
        scenarioElement.setAttribute("xmlns:xsi",
                                     "http://www.w3.org/2001/XMLSchema-instance");
        // Done by translate() method
        // scenarioElement.setAttribute("xsi:noNamespaceSchemaLocation",
        // "scenario.xsd");
        if (!scenarioElement.hasAttribute("wuID"))
            scenarioElement.setAttribute("wuID", "0");
        if (!scenarioElement.hasAttribute("assimMode"))
            scenarioElement.setAttribute("assimMode", "0");
        Element elt = getChildElement(scenarioElement, "entoData");
        if (elt != null && elt.hasAttribute("firstDay")) {
            System.out.println("Warning: Removed firstDay attribute");
            elt.removeAttribute("firstDay");
        }
        //FIXME: may occur more than once
        elt = (Element) scenarioElement.getElementsByTagName("changeEIR").item(
                  0);
        if (elt != null && elt.hasAttribute("firstDay")) {
            System.out.println("Warning: Removed firstDay attribute");
            elt.removeAttribute("firstDay");
        }
        elt = (Element) scenarioElement.getElementsByTagName("parameters")
              .item(0);
        if (elt != null && elt.hasAttribute("useIseed"))
            elt.removeAttribute("useIseed");

        NodeList sourcesElements = scenarioElement
                                   .getElementsByTagName("sources");
        while ( sourcesElements.getLength() > 0 ) {
            Element sourcesElement = (Element) sourcesElements.item(0); // always use index 0 because items are removed
            Element parent = (Element) sourcesElement.getParentNode();
            parent.removeChild(sourcesElement); // this removes an item from sourcesElements
            parent.setNodeValue("");
        }

        NodeList itemsElements = scenarioElement.getElementsByTagName("item");
        for (int index = 0; index < itemsElements.getLength(); index++) {
            Element itemsElement = (Element) itemsElements.item(index);
            itemsElement.setTextContent("");
        }
        Element paramElement = ((Element) scenarioElement.getElementsByTagName(
                                    "parameters").item(0));
        if (paramElement != null) {
            int nspore = Integer.parseInt(paramElement.getAttribute("nspore"));
            paramElement.setAttribute("eipDuration", Integer
                                      .toString(nspore * 5));
            paramElement.removeAttribute("nspore");
        }
        NodeList mdaElements = scenarioElement.getElementsByTagName("MDA");
        for (int index = 0; index < mdaElements.getLength(); index++) {
            Element el = (Element) mdaElements.item(index);
            el.setAttribute("minAge", "0");
            el.setAttribute("maxAge", "99");
            el.setAttribute("coverage", "1");
        }
        NodeList allElements = scenarioElement.getElementsByTagName("*");
        for (int index = 0; index < allElements.getLength(); index++) {
            Element el = (Element) allElements.item(index);
            if (el.hasAttribute("best")) {
                String value = el.getAttribute("best");
                el.setAttribute("value", value);
                el.removeAttribute("best");
            }
        }
        return true;
    }

    public Boolean translate2To3() {
        return true;
    }

    /*
     * EntoData now has either a nonVector or a vector element; EIRDaily and
     * anopheles lists have moved to one of these. Some unwanted entomological
     * parameters have been removed, many have been added, and eipDuration has
     * been moved from param.
     */
    public Boolean translate3To4() {
        try {
            Element params = getChildElement(scenarioElement, "parameters");
            Attr eip = null;
            if (params != null) {
                eip = params.getAttributeNode("eipDuration");
                params.removeAttributeNode(eip);
            }

            // Attribute added to nonVector, if used, below.

            Element elt = getChildElement(scenarioElement, "entoData");
            if (elt != null) {
                NodeList list = elt.getElementsByTagName("EIRDaily");
                if (list.getLength() != 0) {
                    Element nonVector = scenarioDocument.createElement("nonVector");
                    elt.appendChild(nonVector);
                    // NOTE: results don't seem to be right if iterating forwards
                    // instead of backwards (list is "live" view)
                    for (int i = list.getLength() - 1; i >= 0; --i) {
                        // System.out.println (list.getLength());
                        Node eir = list.item(i);
                        // add the element at the new location, remove from old:
                        // unfortunately messes up the whitespace
                        nonVector.insertBefore(eir, nonVector.getFirstChild());
                    }
                    if (eip != null) {
                        nonVector.setAttributeNode(eip);
                    } else {
                        nonVector.setAttribute("eipDuration", "10");
                    }
                }
                list = elt.getElementsByTagName("anopheles");
                if (list.getLength() != 0) {
                    Element vector = scenarioDocument.createElement("vector");
                    elt.appendChild(vector);
                    for (int i = list.getLength() - 1; i >= 0; --i) {
                        Node anoph = list.item(i);
                        ((Element) anoph).removeAttribute("useNv0Guess");
                        vector.insertBefore(anoph, vector.getFirstChild());
                    }
                }

                elt.removeAttribute("inputType");
            }

            elt = getChildElement(scenarioElement, "interventions");
            if (elt != null) {
                Element timed = getChildElement(elt, "timed");
                if (timed != null) {
                    // NOTE: should check whole "intervention" list
                    Element interv = (Element) timed.getElementsByTagName(
                                         "intervention").item(0);
                    if (interv != null) {
                        Element cEIR = getChildElement(interv, "changeEIR");
                        if (cEIR != null) {
                            cEIR.removeAttribute("inputType");
                            cEIR.removeAttribute("name"); // part of EntoData not
                            // NonVector
                            if (eip != null) {
                                cEIR.setAttributeNode((Attr) eip.cloneNode(true));
                            } else {
                                cEIR.setAttribute("eipDuration", "10");
                            }
                        }
                    }
                }
            }
            return true;
        } catch (DocException e) {
            System.err.println("Error: "+e.getMessage());
            return false;
        }
    }

    // modelVersion flags 1<<2, 1<<4 or 1<<5 have changed
    public Boolean translate4To5() {
        int ver = Integer
                  .parseInt(scenarioElement.getAttribute("modelVersion"));
        if ((ver & 0x68) != 0) {// modelVersion with flags 1<<2, 1<<4 or 1<<5
            if ((ver & (1 << 5)) == 0) {
                ver = ver & (1 << 2);
            } else if ((ver & 0x68) == (1 << 5)) {
                ver = (ver ^ (1 << 5)) /* remove 1<<5 flag */
                      & (1 << 4); /* and add 1<<4 flag */
                System.err
                .println("Warning: Scenario uses LOGNORMAL_MASS_ACTION_PLUS_PRE_IMM which has had a bug fixed!");
            } else {
                System.err.println("Error: Scenario had a combination of InfectionIncidenceModel flags - this was invalid!");
                return false;
            }
        }
        return true;
    }

    public Boolean translate5To6() throws Exception {
        int ver = Integer
                  .parseInt(scenarioElement.getAttribute("modelVersion"));
        Element cMs = (Element) scenarioElement.getElementsByTagName(
                          "caseManagements").item(0);
        // wuID is added by add_work.cpp
        scenarioElement.removeAttribute("wuID");
        if ((ver & 8192) != 0) { // ClinicalEventScheduler (new case
            // management)
            if (scenarioElement.getElementsByTagName("healthSystem")
                    .getLength() > 0)
                System.err
                .println("Warning: healthSystem element present but not used");
        } else {
            if (cMs != null)
                System.err
                .println("Warning: caseManagement element present but not used (updating anyway)");
        }
        if (cMs == null)
            return true; // element may not exist, in which case there's
        // nothing to
        // do
        NodeList cMList = cMs.getElementsByTagName("caseManagement");
        for (int i = 0; i < cMList.getLength(); ++i) {
            Element cM = (Element) cMList.item(i);
            cM.removeAttribute("minAgeYrs");
            Element nmfNP = getChildElement(cM, "nmf");
            scenarioDocument.renameNode(nmfNP, null, "nmfNP");
            Element nmfP = (Element) nmfNP.cloneNode(true);
            scenarioDocument.renameNode(nmfP, null, "nmfP");
            cM.insertBefore(nmfP, nmfNP);
        }
        return true;
    }

    // Version 7 added elements for ITN and IRS intervention descriptions.
    // Nothing old needs to be changed.
    public Boolean translate6To7() {
        return true;
    }

    // Version 8 moved emergence rates and some other parameters into the XML
    // file. The relevant test scenarios have already been converted.
    public Boolean translate7To8() {
        try {
            Element eD = getChildElement(scenarioElement, "entoData");
            Element vect = getChildElement(eD, "vector");
            if (vect != null) {
                Element anoph = getChildElement(vect, "anopheles");
                Element mosq = getChildElement(anoph, "mosq");
                // This was required, so this if should always be true:
                if (mosq.getAttribute("emergenceRateFilename") != null) {
                    System.err
                    .println("Warning: emergence rate data is now stored in the scenario document. Update by hand or run with \"openMalaria --enableERC\"");
                    mosq.removeAttribute("emergenceRateFilename");
                }
            }
            return true;
        } catch (DocException e) {
            System.err.println("Error: "+e.getMessage());
            return false;
        }
    }

    // This changed some stuff to do with non-human hosts that wasn't used
    // before and added a VectorAvailability intervention.
    public Boolean translate8To9() {
        return true;
    }

    // Version 10 introduced PKPD description parameters. No changes to
    // existing elements.
    public Boolean translate9To10() {
        return true;
    }

    // Version 11 removes cached emerge rates from the schema
    public Boolean translate10To11() {
        try {
            Element eD = getChildElement(scenarioElement, "entoData");
            Element vect = getChildElement(eD, "vector");
            if (vect != null) {
                NodeList species = vect.getElementsByTagName("anopheles");
                for (int i = 0; i < species.getLength(); ++i) {
                    Element anoph = (Element) species.item(i);
                    Element er = getChildElement(anoph, "emergence");
                    if (er != null)
                        anoph.removeChild(er);
                    // These are from the parameter values based on Anopheles
                    // gambiae in
                    // Namawala, Tanzania, from the paper on comparing
                    // interventions.
                    anoph.setAttribute("propInfected", "0.078");
                    anoph.setAttribute("propInfectious", "0.021");
                }
                System.err
                .println("New attributes propInfected and propInfectious created with default values - please correct (for each anopheles section)!");
            }
            return true;
        } catch (DocException e) {
            System.err.println("Error: "+e.getMessage());
            return false;
        }
    }

    // Version 12 removed the simulationDuration attribute and changed the
    // event-scheduler data (no real scenarios yet so this is not auto-updated).
    public Boolean translate11To12() {
        Element cms = (Element) scenarioElement.getElementsByTagName(
                          "caseManagements").item(0);
        if (cms != null) {
            System.err
            .println("Please replace the caseManagements element with an EventScheduler element (auto-update not implemented)");
            return false;
        }
        scenarioElement.removeAttribute("simulationDuration");
        return true;
    }

    // Version 13 replaced the modelVersion list with a ModelOptions section.
    // Similarly, the summaryOption attribute was replaced with a SurveyOptions
    // element.
    // Also, the GARKI_DENSITY_BIAS model option was introduced.
    // As such, old scenarios are definitely incompatible with the new code.
    public Boolean translate12To13() {
        final int NUM_VERSIONS = 23;
        String[] num2String = new String[NUM_VERSIONS];
        num2String[1] = "PENALISATION_EPISODES";
        num2String[2] = "NEGATIVE_BINOMIAL_MASS_ACTION";
        num2String[3] = "ATTENUATION_ASEXUAL_DENSITY";
        num2String[4] = "LOGNORMAL_MASS_ACTION";
        num2String[5] = "NO_PRE_ERYTHROCYTIC";
        num2String[6] = "MAX_DENS_CORRECTION";
        num2String[7] = "INNATE_MAX_DENS";
        num2String[8] = "MAX_DENS_RESET";
        num2String[9] = "DUMMY_WITHIN_HOST_MODEL";
        num2String[10] = "PREDETERMINED_EPISODES";
        num2String[11] = "NON_MALARIA_FEVERS";
        num2String[12] = "INCLUDES_PK_PD";
        num2String[13] = "CLINICAL_EVENT_SCHEDULER";
        num2String[14] = "MUELLER_PRESENTATION_MODEL";
        num2String[15] = "TRANS_HET";
        num2String[16] = "COMORB_HET";
        num2String[17] = "TREAT_HET";
        num2String[18] = "COMORB_TRANS_HET";
        num2String[19] = "TRANS_TREAT_HET";
        num2String[20] = "COMORB_TREAT_HET";
        num2String[21] = "TRIPLE_HET";
        num2String[22] = "EMPIRICAL_WITHIN_HOST_MODEL";

        Element modelOptions = scenarioDocument.createElement("ModelOptions");
        int verFlags = Integer.parseInt(scenarioElement
                                        .getAttribute("modelVersion"));

        for (int i = 1; i < NUM_VERSIONS; ++i) {
            if ((verFlags & (1 << i)) != 0) {
                Element opt = scenarioDocument.createElement("option");
                opt.setAttribute("name", num2String[i]);
                opt.setAttribute("value", "true");
                modelOptions.appendChild(opt);
            }
        }
        if ((verFlags & (1 << 6)) == 0 && (verFlags & (1 << 9)) == 0
                && (verFlags & (1 << 22)) == 0) {
            if (maxDensBug == BugCorrectionBehaviour.correct) {
                // option is enabled by default so don't need to add it
            } else if (maxDensBug == BugCorrectionBehaviour.dontCorrect) {
                Element opt = scenarioDocument.createElement("option");
                opt.setAttribute("name", num2String[6]);
                opt.setAttribute("value", "false");
                modelOptions.appendChild(opt);
            } else {
                System.err
                .println("scenario doesn't include MAX_DENS_CORRECTION: please specify --maxDensCorrection BOOL");
                return false;
            }
        }

        scenarioElement.insertBefore(modelOptions, scenarioElement
                                     .getFirstChild());
        scenarioElement.removeAttribute("modelVersion");

        final int NUM_SURVEYS = 31;
        String[] surveyCode2String = new String[NUM_SURVEYS];
        surveyCode2String[0] = "nHost";
        surveyCode2String[1] = "nInfect";
        surveyCode2String[2] = "nExpectd";
        surveyCode2String[3] = "nPatent";
        surveyCode2String[4] = "sumLogPyrogenThres";
        surveyCode2String[5] = "sumlogDens";
        surveyCode2String[6] = "totalInfs";
        surveyCode2String[7] = "nTransmit";
        surveyCode2String[8] = "totalPatentInf";
        surveyCode2String[9] = "contrib";
        surveyCode2String[10] = "sumPyrogenThresh";
        surveyCode2String[11] = "nTreatments1";
        surveyCode2String[12] = "nTreatments2";
        surveyCode2String[13] = "nTreatments3";
        surveyCode2String[14] = "nUncomp";
        surveyCode2String[15] = "nSevere";
        surveyCode2String[16] = "nSeq";
        surveyCode2String[17] = "nHospitalDeaths";
        surveyCode2String[18] = "nIndDeaths";
        surveyCode2String[19] = "nDirDeaths";
        surveyCode2String[20] = "nEPIVaccinations";
        surveyCode2String[21] = "imr_summary";
        surveyCode2String[22] = "nMassVaccinations";
        surveyCode2String[23] = "nHospitalRecovs";
        surveyCode2String[24] = "nHospitalSeqs";
        surveyCode2String[25] = "nIPTDoses";
        surveyCode2String[26] = "annAvgK";
        surveyCode2String[27] = "nNMFever";
        surveyCode2String[28] = "innoculationsPerDayOfYear";
        surveyCode2String[29] = "kappaPerDayOfYear";
        surveyCode2String[30] = "innoculationsPerAgeGroup";

        Element surveyOptions = scenarioDocument.createElement("SurveyOptions");
        Element monitoring, surveys;
        try {
            monitoring = getChildElement(scenarioElement, "monitoring");
            surveys = getChildElement(monitoring, "surveys");
        } catch (DocException e) {
            System.err.println("Error: "+e.getMessage());
            return false;
        }
        int surveyFlags = Integer.parseInt(surveys
                                           .getAttribute("summaryOption"));

        for (int i = 0; i < NUM_SURVEYS; ++i) {
            if ((surveyFlags & (1 << i)) != 0) {
                Element opt = scenarioDocument.createElement("option");
                opt.setAttribute("name", surveyCode2String[i]);
                opt.setAttribute("value", "true");
                surveyOptions.appendChild(opt);
            }
        }

        monitoring.insertBefore(surveyOptions, surveys);
        surveys.removeAttribute("summaryOption");

        int analysisNum = Integer.parseInt(scenarioElement
                                           .getAttribute("analysisNo"));
        // These analysis numbers _were_ reserved for Garki scenarios.
        if ((analysisNum >= 22) && (analysisNum <= 30)) {
            Element opt = scenarioDocument.createElement("option");
            opt.setAttribute("name", "GARKI_DENSITY_BIAS");
            opt.setAttribute("value", "true");
            modelOptions.appendChild(opt);
        }

        return true;
    }

    // Version 14 changed the drugDescription element. This was as-yet unused
    // and there's no direct
    // translation from the old version.
    public Boolean translate13To14() {
        Node cms = scenarioElement.getElementsByTagName("drugDescription").item(0);
        if (cms != null) {
            System.err
            .println("Warning: drugDescription element has changed; please rewrite manually.");
        }
        return true;
    }

    // Version 15 allowed MDA interventions to include drug information (no
    // changes to existing scenarios)
    public Boolean translate14To15() {
        return true;
    }

    public Boolean translate15To16() {
        try {
            Element model = scenarioDocument.createElement("model");
            Element clinical = scenarioDocument.createElement("clinical");
            Element modelOptions = (Element) scenarioElement.getElementsByTagName(
                                       "ModelOptions").item(0);
            Element parameters = getChildElement(scenarioElement, "parameters");

            model.appendChild(modelOptions);
            model.appendChild(clinical);
            model.appendChild(parameters);

            scenarioElement.appendChild(model);

            Element healthSystemOld = getChildElement(scenarioElement, "healthSystem");
            Element eventScheduler = (Element) scenarioElement
                                     .getElementsByTagName("EventScheduler").item(0);
            Attr healthSystemMemory;

            Element healthSystemNew = scenarioDocument
                                      .createElement("healthSystem");

            if (healthSystemOld == null) {
                healthSystemMemory = eventScheduler
                                     .getAttributeNode("healthSystemMemory");
                eventScheduler.removeAttribute("healthSystemMemory");
                // scenarioDocument.renameNode(eventScheduler, null,
                // "HSEventScheduler");
                Element CFR = scenarioDocument.createElement("CFR");
                Element group = scenarioDocument.createElement("group");
                Attr cfr = scenarioDocument.createAttribute("cfr");
                Attr lowerbound = scenarioDocument.createAttribute("lowerbound");

                cfr.setNodeValue("0");
                lowerbound.setNodeValue("0");

                group.setAttributeNode(cfr);
                group.setAttributeNode(lowerbound);

                CFR.appendChild(group);

                healthSystemNew.appendChild(eventScheduler);
                healthSystemNew.appendChild(CFR);
            } else {
                healthSystemMemory = healthSystemOld
                                     .getAttributeNode("healthSystemMemory");
                healthSystemOld.removeAttribute("healthSystemMemory");
                // healthSystemOld.removeAttribute("name");
                scenarioDocument.renameNode(healthSystemOld, null,
                                            "ImmediateOutcomes");
                Element CFR = getChildElement(healthSystemOld, "CFR");

                healthSystemNew.appendChild(healthSystemOld);
                healthSystemNew.appendChild(CFR);
            }

            // FIXME: could be more than one intervention with changeHS
            Element Intervention = (Element) scenarioElement.getElementsByTagName(
                                       "intervention").item(0);
            if (Intervention != null) {
                Element changeHS = getChildElement(Intervention, "changeHS");

                if (changeHS != null) {
                    changeHS.removeAttribute("healthSystemMemory");

                    scenarioDocument
                    .renameNode(changeHS, null, "ImmediateOutcomes");

                    Element changeHSNew = scenarioDocument
                                          .createElement("changeHS");
                    Intervention.appendChild(changeHSNew);
                    changeHSNew.appendChild(changeHS);

                    Element HSCFR = getChildElement(changeHS, "CFR");
                    changeHSNew.appendChild(HSCFR);
                }

            }

            scenarioElement.insertBefore(healthSystemNew, getChildElement(scenarioElement, "entoData"));
            clinical.setAttributeNode(healthSystemMemory);

            return true;
        } catch (DocException e) {
            System.err.println("Error: "+e.getMessage());
            return false;
        }
    }

    public Boolean translate16To17() {

        Element vector = (Element) scenarioElement.getElementsByTagName(
                             "vector").item(0);

        if (vector != null) {
            NodeList anopheles = vector.getElementsByTagName("anopheles");

            if (((Element) anopheles.item(0)).getElementsByTagName(
                        "nonHumanHosts").getLength() > 0) {
                Element nhh = (Element) ((Element) anopheles.item(0))
                              .getElementsByTagName("nonHumanHosts").item(0);

                Attr nonHumanHostsnumber = scenarioDocument
                                           .createAttribute("number");
                nonHumanHostsnumber.setNodeValue(Double
                                                 .toString(SchemaTranslator.Standard_NHH_NUMBER));
                Attr name = scenarioDocument.createAttribute("name");
                name.setNodeValue(nhh.getAttribute("name"));

                Element nonHumanHostsnumbers = scenarioDocument
                                               .createElement("nonHumanHosts");
                nonHumanHostsnumbers.setAttributeNode(name);
                nonHumanHostsnumbers.setAttributeNode(nonHumanHostsnumber);

                vector.appendChild(nonHumanHostsnumbers);
            }

            for (int i = 0; i < anopheles.getLength(); i++) {
                Element anophelesType = (Element) anopheles.item(i);
                String typeName = anophelesType.getAttribute("mosquito");
                NodeList nonHumanHosts = anophelesType
                                         .getElementsByTagName("nonHumanHosts");
                Element mosq = (Element) anophelesType.getElementsByTagName(
                                   "mosq").item(0);

                if (typeName.equals(SchemaTranslator.Name_GAMBIAE_SS))
                    setMosqsNewAttributes(SchemaTranslator.INDEX_GAMBIAE_SS,
                                          mosq, nonHumanHosts);

                else if (typeName.equals(SchemaTranslator.Name_FUNESTUS))
                    setMosqsNewAttributes(SchemaTranslator.INDEX_FUNESTUS,
                                          mosq, nonHumanHosts);

                else if (typeName.equals(SchemaTranslator.Name_ARABIENSIS))
                    setMosqsNewAttributes(SchemaTranslator.INDEX_ARABIENSIS,
                                          mosq, nonHumanHosts);

                else {
                    System.err
                    .println("There are no standards values for this kind of mosquito. Please edit those values per hand. ");
                    System.err.println("This scenario will not be updated.");
                    return false;
                }
            }
        }
        return true;
    }

    public Boolean translate17To18() {
        Attr popSize = scenarioElement.getAttributeNode("popSize");
        Attr maxAgeYrs = scenarioElement.getAttributeNode("maximumAgeYrs");
        Attr mode = scenarioElement.getAttributeNode("mode");

        scenarioElement.removeAttribute("popSize");
        scenarioElement.removeAttribute("maximumAgeYrs");
        scenarioElement.removeAttribute("mode");

        Element demography, entoData;
        try {
            demography = getChildElement(scenarioElement, "demography");
            entoData = getChildElement(scenarioElement, "entoData");
        } catch (DocException e) {
            System.err.println("Error: "+e.getMessage());
            return false;
        }

        demography.setAttributeNode(popSize);
        demography.setAttributeNode(maxAgeYrs);

        entoData.setAttributeNode(mode);

        return true;
    }

    // Version 19: entoData's "mode" can no longer have value 3.
    // Removed unused "delta" from parameters.
    // Moved two event-scheduler outcome attributes into parameters element -- updated by hand.
    public Boolean translate18To19() {
        try {
            Element entoData = getChildElement(scenarioElement, "entoData");
            Attr mode = entoData.getAttributeNode("mode");

            if (Integer.parseInt(mode.getValue()) == 3) {
                // unless an intervention at time 0 specifies EIR values, the scenario was buggy
                boolean hasTransientEIRAt0 = false;

                Element elt = getChildElement(scenarioElement, "interventions");
                if (elt != null) {
                    Element timed = getChildElement(elt, "timed");
                    if (timed != null) {
                        NodeList intervs = timed.getElementsByTagName("intervention");
                        for (int i = 0; i < intervs.getLength(); i++) {
                            Element interv = (Element) intervs.item(i);
                            if (Integer.parseInt(interv.getAttribute("time")) != 0)
                                continue;       // only interested in interv. at time 0

                            Element cEIR = getChildElement(interv, "changeEIR");
                            if (cEIR != null)   // yes, have applicable transient EIR data
                                hasTransientEIRAt0 = true;
                        }
                    }
                }
                if (!hasTransientEIRAt0) {
                    System.err.println("Error: entoData has mode=\"3\", but no changeEIR intervention found at time 0.");
                    return false;
                }

                // Assuming correct changeEIR intervention was found, we can just switch mode to 4.
                mode.setValue ("4");
            }

            Element model = getChildElement(scenarioElement, "model");
            if (model!=null)
            {
                Element t_parameters = getChildElement(model, "parameters");
                if (t_parameters!=null)
                    t_parameters.removeAttribute("delta");
            }

            return true;
        } catch (DocException e) {
            System.err.println("Error: "+e.getMessage());
            return false;
        }
    }

    // Version 20
    // Added monitoring -> continuous -> duringInit optional attribute (no translation needed)
    // monitoring -> continuous -> period changed units from days to timesteps
    // IPTI_SP_MODEL option added
    // imr_summary changed name to allCauseIMR
    // minInfectedThreshold attribute was added to anopheles sections
    // pSequelaeInpatient data moved from ImmediateOutcomes to parent HealthSystem element, and changed form.
    public Boolean translate19To20() {
        try {
            Element monitoring = getChildElement(scenarioElement, "monitoring");
            Element ctsMon = getChildElement(monitoring, "continuous");
            if ( ctsMon != null ) {
                // Guess the common update. If not, complain.
                if ( ctsMon.getAttribute("period").equals("5") )
                    ctsMon.setAttribute("period", "1");
                else
                    System.err.println("Warning: monitoring->continuous->period changed unit from timesteps to days. Please update accordingly.");
            }

            Element SurveyOptions = getChildElement(monitoring, "SurveyOptions");
            NodeList options = SurveyOptions.getElementsByTagName("option");
            for (int i = 0; i < options.getLength(); ++i) {
                Element option = (Element) options.item(i);
                if (option.getAttribute("name").equals( "imr_summary" )) {
                    option.setAttribute("name", "allCauseIMR");
                }
            }

            Element eD = getChildElement(scenarioElement, "entoData");
            Element vect = getChildElement(eD, "vector");
            if (vect != null) {
                NodeList species = vect.getElementsByTagName("anopheles");
                for (int i = 0; i < species.getLength(); ++i) {
                    Element anoph = (Element) species.item(i);
                    Element mosq = getChildElement(anoph, "mosq");
                    mosq.setAttribute("minInfectedThreshold", "0.01");
                }
                System.err
                .println("New attribute minInfectedThreshold created with default 0.01 mosquito - please correct (for each anopheles section)!");
            }

            // IPTI_SP_MODEL option (try to work out whether it should be used)
            Element interventions = getChildElement(scenarioElement, "interventions");
            if (interventions != null) {
                Element iptiDesc = getChildElement(interventions, "iptiDescription");
                if (iptiDesc != null) {
                    int nIPTI = 0;
                    Element continuous = getChildElement(interventions, "continuous");
                    if (continuous != null) {
                        nIPTI += continuous.getElementsByTagName("ipti").getLength();
                    }
                    Element timed = getChildElement(interventions, "timed");
                    if (timed != null) {
                        NodeList tIntervs = timed.getElementsByTagName("intervention");
                        for (int i = 0; i < tIntervs.getLength(); ++i) {
                            Element tInterv = (Element)tIntervs.item(i);
                            nIPTI += tInterv.getElementsByTagName("ipti").getLength();
                        }
                    }

                    Element modelElement = getChildElement(scenarioElement, "model");
                    Element modelOptions = getChildElement(modelElement, "ModelOptions");
                    Element iptiOption = scenarioDocument.createElement("option");
                    iptiOption.setAttribute("name", "IPTI_SP_MODEL");

                    if (nIPTI>0) {
                        // Definitely need IPTI model
                        iptiOption.setAttribute("value","true");
                        modelOptions.appendChild(iptiOption);
                    } else {
                        // Don't need IPTI model; either it was added on purpose
                        // (to get results comparable to when using IPTI interventions)
                        // or it was a mistake. Require user to decide which.
                        System.err.println("Warning: iptiDescription without IPT interventions");
                        if (iptiSpOption == IptiSpBehaviour.assumeIntended) {
                            iptiOption.setAttribute("value","true");
                            modelOptions.appendChild(iptiOption);
                        } else if (iptiSpOption == IptiSpBehaviour.assumeUnintented) {
                            iptiOption.setAttribute("value","false");   // make it clear IPTI code is disabled
                            modelOptions.appendChild(iptiOption);
                        } else {
                            System.err.println("Error: please specify --iptiSpOptionWithoutInterventions");
                            return false;
                        }
                    }
                } else {
                    // no iptiDescription so no need for IPT model; don't add option
                }
            }

            Element hs = getChildElement(scenarioElement, "healthSystem");
            translateHealthSystem19To20( hs );
            if (interventions != null) {
                Element timed = getChildElement(interventions, "timed");
                if (timed != null) {
                    NodeList changeHS = timed.getElementsByTagName("changeHS");
                    for (int i = 0; i < changeHS.getLength(); ++i) {
                        if ( !translateHealthSystem19To20( (Element)changeHS.item(i) ) )
                            return false;
                    }
                }
            }

            return true;
        } catch (DocException e) {
            System.err.println("Error: "+e.getMessage());
            return false;
        }
    }
    double[] readV19PSequelaeInpatientValues (Element oldPSeq) {
        /* The expected upper bounds. At some point previously in the code, age
        groups as specified in the XML were misleadingly remapped: age groups
        could be ignored or have different bounds. To avoid letting non-corresponding
        entries now have a different effect, we check the bounds correspond exactly
        to what we expected (and which, as far as I am aware, was always the case). */
        double[] ubound = new double[] { 5, 99 };

        NodeList items = oldPSeq.getElementsByTagName("item");
        if (items.getLength() != ubound.length)
            return null;

        double[] values = new double[ubound.length];
        for ( int i=0; i<values.length; ++i ) {
            if ( Double.parseDouble(((Element)items.item(i)).getAttribute("maxAgeYrs")) != ubound[i] )
                return null;    // require exact match
            values[i] = Double.parseDouble(((Element)items.item(i)).getAttribute("value"));
        }
        return values;
    }
    boolean translateHealthSystem19To20 (Element hs) {
        try {
            // pSequelaeInpatient update
            Element hsio = getChildElement(hs, "ImmediateOutcomes");
            double[] pSeqGroupLBound = new double[] { 0.0, 5.0 };
            double[] pSeqGroupValue;
            if ( hsio != null ) {
                Element oldPSeq = getChildElement(hsio, "pSequelaeInpatient");
                pSeqGroupValue = readV19PSequelaeInpatientValues( oldPSeq );
                if ( pSeqGroupValue == null ) {
                    System.err.println("Error: expected pSequelaeInpatient to have two age-groups: 0-5 and 5-99");
                    return false;
                }
                hsio.removeChild( oldPSeq );
            } else {    // using EventScheduler model which didn't previously have sequelae data
                System.err.println("Warning: pSequelaeInpatient element with default data added");
                // I guess we can do this, since, as far as I am aware, this same data-set
                // has always been used, apart from where zero sequelae is desired.
                pSeqGroupValue = new double[] { 0.0132, 0.005 };
            }
            if ( pSeqGroupLBound.length != pSeqGroupValue.length ) throw new DocException("length mismatch!");
            Element pSeqGroups = scenarioDocument.createElement("pSequelaeInpatient");
            for (int i = 0; i < pSeqGroupValue.length; ++i) {
                Element group = scenarioDocument.createElement("group");
                group.setAttribute("value", Double.toString(pSeqGroupValue[i]));
                group.setAttribute("lowerbound", Double.toString(pSeqGroupLBound[i]));
                pSeqGroups.appendChild( group );
            }
            hs.appendChild( pSeqGroups );

            // CFR element changed
            Element cfrElt = getChildElement(hs, "CFR");
            NodeList cfrList = cfrElt.getElementsByTagName("group");
            for ( int i = 0; i < cfrList.getLength(); ++i ) {
                Element group = (Element)cfrList.item(i);
                String val = group.getAttribute("cfr");
                group.removeAttribute("cfr");
                group.setAttribute("value",val);
            }
            return true;
        } catch (DocException e) {
            System.err.println("Error: "+e.getMessage());
            return false;
        }
    }

    /* Moved all intervention descriptions into a sub-element. */
    public Boolean translate20To21() {
        try {
            Element intervs = getChildElement( scenarioElement, "interventions" );
            Element descs = scenarioDocument.createElement( "descriptions" );
            intervs.insertBefore( descs, intervs.getFirstChild() );
            Node elt = descs.getNextSibling();
            while ( elt != null ) {
                Node next = elt.getNextSibling();

                if ( elt instanceof Element
                        && !elt.getNodeName().equals( "continuous" )
                        && !elt.getNodeName().equals( "timed" )
                   ) {
                    descs.appendChild( elt );
                }

                elt = next;
            }
            return true;
        } catch (DocException e) {
            System.err.println("Error: "+e.getMessage());
            return false;
        }
    }

    /* Schema 22 adds some optional features to facilitate EIR entry.
     No translation required. */
    public Boolean translate21To22() {
        return true;
    }

    /* REPORT_ONLY_AT_RISK option added, potentially changing the behaviour
    of scenarios using IPTI_SP_MODEL. */
    public Boolean translate22To23() {
        try {
            if ( usesOption( "IPTI_SP_MODEL" ) ) {
                if ( iptiROAR == IptiReportOnlyAtRiskBehaviour.off ) {
                    // do nothing
                } else if ( iptiROAR == IptiReportOnlyAtRiskBehaviour.on ) {
                    Element modelElement = getChildElement(scenarioElement, "model");
                    Element modelOptions = getChildElement(modelElement, "ModelOptions");
                    Element roarOption = scenarioDocument.createElement("option");
                    roarOption.setAttribute("name", "REPORT_ONLY_AT_RISK");
                    roarOption.setAttribute("value","true");
                    modelOptions.appendChild(roarOption);
                } else {
                    System.err.println("Error: please specify --iptiReportOnlyAtRisk");
                    return false;
                }
            }
            return true;
        } catch (DocException e) {
            System.err.println("Error: "+e.getMessage());
            return false;
        }
    }

    /* Units of EIR inputs in vector model changed.
     * assimMode attribute removed.
     * Human weight and availability to mosquito age-group data moved out of
     * code and into XML. */
    public Boolean translate23To24() {
        try {
            //Remove assimMode:
            if (!scenarioElement.getAttribute("assimMode").equals("0")) {
                System.err.println("Error: assimMode is no longer supported");
                return false;
            }
            scenarioElement.removeAttribute("assimMode");
            //Warn about change in EIR units, but don't update automatically (intended unit unknown):
            if ( getChildNodes(getChildElement(scenarioElement, "entoData"), "vector").size() > 0 ) {
                System.err.println("Warning: units of EIR for vector model changed from inoculations per averaged person to inoculations per average adult.");
            }

            //Add human element into scenario:
            // Data from Tanzanian survey, as was previously hard-coded within OpenMalaria
            double[] groupLbounds = new double[] { 0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,20,20 };
            double[] groupAvMosq = new double[] {
                0.225940909648,0.286173633441,0.336898395722,0.370989854675,
                0.403114915112,0.442585112522,0.473839351511,0.512630464378,
                0.54487872702,0.581527755812,0.630257580698,0.663063362714,
                0.702417432755,0.734605377277,0.788908765653,0.839587932303,
                1.0,1.0
            };
            double[] groupWeight = new double[] {
                13.9856718,18.30372108,21.745749,24.25753512,
                26.06595444,28.48868784,30.84202788,33.48638244,
                35.20335432,37.19394024,40.1368962,42.00539916,
                44.53731348,46.77769728,49.48396092,54.36,
                60.0,60.0
            };
            assert groupLbounds.length == groupAvMosq.length;
            assert groupLbounds.length == groupWeight.length;

            Element model = getChildElement(scenarioElement,"model");
            Element human = scenarioDocument.createElement("human");
            model.insertBefore(human, getChildElement(model,"parameters"));
            Element avMosq = scenarioDocument.createElement("availabilityToMosquitoes");
            human.appendChild(avMosq);
            for (int i=0;i<groupLbounds.length;i++) {
                Element g = scenarioDocument.createElement("group");
                avMosq.appendChild(g);
                g.setAttribute("lowerbound",Double.toString(groupLbounds[i]));
                g.setAttribute("value",Double.toString(groupAvMosq[i]));
            }
            if ( Integer.parseInt( getChildElement(model,"parameters").getAttribute("interval") ) == 1 ) {
                // Add weight data, only used with PkPd model
                Element weight = scenarioDocument.createElement("weight");
                human.appendChild(weight);
                for (int i=0;i<groupLbounds.length;i++) {
                    Element g = scenarioDocument.createElement("group");
                    weight.appendChild(g);
                    g.setAttribute("lowerbound",Double.toString(groupLbounds[i]));
                    g.setAttribute("value",Double.toString(groupWeight[i]));
                }
                weight.setAttribute("multStdDev","0.14");   // rough figure from Tanzanian data
            }
            return true;
        } catch (DocException e) {
            System.err.println("Error: "+e.getMessage());
            return false;
        }
    }

    /* Vaccine type was changed from an integer to a string identifier.
     * WeibullDecayedValue replaced with more general DecayFunction interface.
     * MDADescription changed from just a list of drug doses to a full decision tree.
     */
    public Boolean translate24To25() {
        try {
            NodeList vaccs = scenarioElement.getElementsByTagName ("vaccineDescription");
            for (int i = 0; i < vaccs.getLength(); i++) {
                Element vd = (Element)vaccs.item(i);
                int t = Integer.parseInt(vd.getAttribute("vaccineType"));
                if ( t == 1 ) {
                    vd.setAttribute("vaccineType","PEV");
                } else if ( t==2 ) {
                    vd.setAttribute("vaccineType","BSV");
                } else if (t==3) {
                    vd.setAttribute("vaccineType","TBV");
                } else {
                    System.err.println("Unrecognized vaccine type: "+t);
                    return false;
                }

                Element hly = getChildElement(vd,"halfLifeYrs");
                double hl = Double.parseDouble(hly.getAttribute("value"));
                Element decay = scenarioDocument.createElement("decay");
                decay.setAttribute("L",Double.toString(hl));
                if (hl == 0.0) {
                    // special case: no decay
                    decay.setAttribute("function","constant");
                } else {
                    decay.setAttribute("function","exponential");
                }
                vd.replaceChild(decay,hly);
            }

            WeibullDecayedValueToDecayFunction(scenarioElement.getElementsByTagName ("preprandialKillingEffect"));
            WeibullDecayedValueToDecayFunction(scenarioElement.getElementsByTagName ("postprandialKillingEffect"));
            WeibullDecayedValueToDecayFunction(scenarioElement.getElementsByTagName ("killingEffect"));
            WeibullDecayedValueToDecayFunction(scenarioElement.getElementsByTagName ("deterrency"));

            Element MDADesc = (Element) scenarioElement.getElementsByTagName("MDADescription").item(0);
            if ( MDADesc != null ) {
                Element schedule = getChildElement(MDADesc,"schedule");

                // add decisions (HSESDecisions)
                Element decisions = scenarioDocument.createElement("decisions");
                MDADesc.appendChild(decisions);
                // add required decision "test": "none"
                Element dTest = scenarioDocument.createElement("decision");
                dTest.setAttribute("name","test");
                dTest.setAttribute("depends","");
                dTest.setAttribute("values","none,microscopy,RDT");
                dTest.setNodeValue("none");
                decisions.appendChild(dTest);
                // add required decision "treatment"
                Element dTreatment = scenarioDocument.createElement("decision");
                dTreatment.setAttribute("name","treatment");
                dTreatment.setAttribute("depends","");
                dTreatment.setAttribute("values","1");
                dTreatment.setNodeValue("1");
                decisions.appendChild(dTreatment);

                // add treatment list (HSESTreatments)
                Element treatments = scenarioDocument.createElement("treatments");
                MDADesc.appendChild(treatments);
                Element treat1 = scenarioDocument.createElement("treatment");
                treat1.setAttribute("name","1");
                treatments.appendChild(treat1);
                // move original treatment schedule to a sub-element of treatment list
                treat1.appendChild(schedule);
            }

            System.out.println("translated to 25");
            return true;
        } catch (DocException e) {
            System.err.println("Error: "+e.getMessage());
            return false;
        }
    }
    void WeibullDecayedValueToDecayFunction (NodeList nodes) {
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element)nodes.item(i);
            node.setAttribute("L", node.getAttribute("halflife"));      // value should be same for 2 possible distributions
            node.removeAttribute("halflife");
            if (node.hasAttribute("Weibullk")) {
                node.setAttribute("function","weibull");
                node.setAttribute("k",node.getAttribute("Weibullk"));
                node.removeAttribute("Weibullk");
            } else {
                node.setAttribute("function","exponential");
            }
        }
    }

    /* Vector interventions no longer have independent decay functions per
     * effect, but have heterogeneity.
     *
     * Non-malaria fevers/complications: formula for P(antibiotic treatment)
     * changed to use independent values for P(need treatment) in malaria and
     * non-malaria fevers (prNeedTreatmentNMF and prNeedTreatmentMF).
     * Never previously used, so manual update.
     *
     * pImmediateUC replaced by dailyPrImmUCTS. */
    public Boolean translate25To26() {
        try {
            NodeList clinOutcomes = scenarioElement.getElementsByTagName("ClinicalOutcomes");
            for (int i=0; i<clinOutcomes.getLength(); i++) {
                Element ci = (Element) clinOutcomes.item(i);
                Element immUCElt = getChildElement(ci, "pImmediateUC");
                double pImmUC = Double.parseDouble(immUCElt.getTextContent());
                ci.removeChild(immUCElt);
                if (pImmUC == 1) {
                    Element dailyP = scenarioDocument.createElement("dailyPrImmUCTS");
                    dailyP.setTextContent("1");
                    ci.appendChild(dailyP);
                } else {
                    assert pImmUC < 1.0 && pImmUC > 0;
                    System.err.println("pImmediateUC element replaced with dailyPrImmUCTS: not an exact equivalent");
                    double invP = 1-pImmUC;
                    double[] pArr = { pImmUC, invP*pImmUC, invP*invP*pImmUC };
                    double total = pArr[0]+pArr[1]+pArr[2];
                    for (int j=0;j<3;++j) {
                        Element dailyP=scenarioDocument.createElement("dailyPrImmUCTS");
                        dailyP.setTextContent(Double.toString(pArr[j]/total));
                        ci.appendChild(dailyP);
                    }
                }
            }
            Element intervs = getChildElement(scenarioElement,"interventions");
            Element iDescs = getChildElement(intervs,"descriptions");
            List<Node> anophs = getChildNodes(iDescs,"anopheles");
            if (!(
                        t26VecInterv(iDescs,anophs,"ITNDescription","ITNDecay") &&
                        t26VecInterv(iDescs,anophs,"IRSDescription","IRSDecay") &&
                        t26VecInterv(iDescs,anophs,"VADescription","VADecay") ) ) {
                return false;
            }
            return true;
        } catch (DocException e) {
            System.err.println("Error: "+e.getMessage());
            return false;
        }
    }
    private boolean t26VecInterv (Element iDescs,List<Node> anophs, String descriptionElt, String decayElt) throws DocException {
        class VIDesc {
            String function, L, k, initial;
        }
        VIDesc desc = null;
        for ( Node n : anophs ) {
            Element a = (Element) n;
            Element interv;
            interv = getChildElement(a,descriptionElt);
            if (interv != null) {
                NodeList effects = interv.getChildNodes();
                for (int i=0; i<effects.getLength();++i) {
                    if (!(effects.item(i) instanceof Element)) {
                        continue;
                    }
                    Element effect = (Element)effects.item(i);
                    if (desc == null) {
                        desc = new VIDesc();
                        desc.function = effect.getAttribute("function");
                        desc.L = effect.getAttribute("L");
                        if (effect.hasAttribute("k")) {
                            desc.k = effect.getAttribute("k");
                        } else {
                            desc.k="1";
                        }
                    } else {
                        if (!desc.function.equals(effect.getAttribute("function"))) {
                            System.err.println(descriptionElt+": differing decay functions no longer supported. function: "+desc.function+" and "+effect.getAttribute("function"));
                            return false;
                        }
                        if (Double.parseDouble(desc.L) != Double.parseDouble(effect.getAttribute("L"))) {
                            System.err.println(descriptionElt+": differing decay functions no longer supported. L: "+desc.L+" and "+effect.getAttribute("L"));
                            return false;
                        }
                        String k = effect.hasAttribute("k")?effect.getAttribute("k"):"1";
                        if (Double.parseDouble(desc.k) != Double.parseDouble(k)) {
                            System.err.println(descriptionElt+": differing decay functions no longer supported. k: "+desc.k+" and "+k);
                            return false;
                        }
                    }
                    effect.removeAttribute("function");
                    effect.removeAttribute("L");
                    if (effect.hasAttribute("k")) {
                        effect.removeAttribute("k");
                    }
                    effect.setAttribute("value",effect.getAttribute("initial"));
                    effect.removeAttribute("initial");
                }
            }
        }
        if (desc != null) {
            Element newDesc = scenarioDocument.createElement(decayElt);
            newDesc.setAttribute("function",desc.function);
            newDesc.setAttribute("L",desc.L);
            if (Double.parseDouble(desc.k)!=1) {
                newDesc.setAttribute("k",desc.k);
            }
            iDescs.insertBefore(newDesc,anophs.get(0));
        }
        return true;
    }

    /* Nothing needs updating. */
    public Boolean translate26To27() {
        return true;
    }

    /* Several name changes.
     * A major change to the interventions element.
     */
    public Boolean translate27To28() {
        try {
            // Name changes:
            Element ento = getChildElement(scenarioElement,"entoData");
            scenarioDocument.renameNode(ento,null,"entomology");
            Element vec = getChildElement(ento,"vector");
            if ( vec!=null ) {
                for (Node n:getChildNodes(vec,"anopheles")) {
                    for (Node m:getChildNodes((Element)n,"eir")) {
                        scenarioDocument.renameNode(m,null,"EIR");
                    }
                    for (Node m:getChildNodes((Element)n,"monthlyEir")) {
                        scenarioDocument.renameNode(m,null,"monthlyEIR");
                    }
                }
            }
            for (Node n : getChildNodes(scenarioElement,"drugDescription")) {
                scenarioDocument.renameNode(n,null,"pharmacology");
            }

            // Interventions elt changes:
            Element interventions = getChildElement(scenarioElement,"interventions");
            Element descriptions = getChildElement(interventions,"descriptions");
            NodeList descs = descriptions.getChildNodes();
            int l = descs.getLength();
            for ( int i = 0; i < l; ++i ) {
                if (!(descs.item(i) instanceof Element)) continue;
                Element oldElt = (Element) descs.item(i);
                Element newElt = null;
                String descName = oldElt.getNodeName();
                if (descName.equals("iptiDescription")) {
                    newElt = getOrCreateSubElt(interventions,"IPT");
                    newElt.appendChild(oldElt);
                    scenarioDocument.renameNode(oldElt,null,"description");
                } else if (descName.equals("vaccineDescription")) {
                    newElt = getOrCreateSubElt(interventions,"vaccine");
                    newElt.appendChild(oldElt);
                    scenarioDocument.renameNode(oldElt,null,"description");
                } else if (descName.equals("MDADescription")) {
                    newElt = getOrCreateSubElt(interventions,"MDA");
                    newElt.appendChild(oldElt);
                    scenarioDocument.renameNode(oldElt,null,"description");
                } else if (descName.equals("ITNDecay")) {
                    newElt = getOrCreateSubElt(interventions,"ITN");
                    newElt.appendChild(oldElt);
                    scenarioDocument.renameNode(oldElt,null,"decay");
                } else if (descName.equals("IRSDecay")) {
                    newElt = getOrCreateSubElt(interventions,"IRS");
                    newElt.appendChild(oldElt);
                    scenarioDocument.renameNode(oldElt,null,"decay");
                } else if (descName.equals("VADecay")) {
                    newElt = getOrCreateSubElt(interventions,"vectorDeterrent");
                    newElt.appendChild(oldElt);
                    scenarioDocument.renameNode(oldElt,null,"decay");
                } else if (descName.equals("anopheles")) {
                    NodeList aDescs = oldElt.getChildNodes();
                    String mosqName = oldElt.getAttribute("mosquito");
                    int l2 = aDescs.getLength();
                    for ( int j = 0; j < l2; ++j ) {
                        if (!(aDescs.item(j) instanceof Element)) continue;
                        Element oldAElt = (Element) aDescs.item(j);

                        String aDescName = oldAElt.getNodeName();
                        if (aDescName.equals("ITNDescription")) {
                            newElt = getOrCreateSubElt(interventions,"ITN");
                            newElt.appendChild(oldAElt);
                            scenarioDocument.renameNode(oldAElt,null,"anophelesParams");
                            oldAElt.setAttribute("mosquito",mosqName);
                            if (!newElt.hasAttribute("name")) {
                                newElt.setAttribute("name",oldAElt.getAttribute("name"));
                            }
                            oldAElt.removeAttribute("name");
                        } else if (aDescName.equals("IRSDescription")) {
                            newElt = getOrCreateSubElt(interventions,"IRS");
                            newElt.appendChild(oldAElt);
                            scenarioDocument.renameNode(oldAElt,null,"anophelesParams");
                            oldAElt.setAttribute("mosquito",mosqName);
                            if (!newElt.hasAttribute("name")) {
                                newElt.setAttribute("name",oldAElt.getAttribute("name"));
                            }
                            oldAElt.removeAttribute("name");
                        } else if (aDescName.equals("VADescription")) {
                            newElt = getOrCreateSubElt(interventions,"vectorDeterrent");
                            newElt.appendChild(oldAElt);
                            scenarioDocument.renameNode(oldAElt,null,"anophelesParams");
                            oldAElt.setAttribute("mosquito",mosqName);
                            if (!newElt.hasAttribute("name")) {
                                newElt.setAttribute("name",oldAElt.getAttribute("name"));
                            }
                            oldAElt.removeAttribute("name");
                        } else {
                            System.err.println("Unexpected element interventions.descriptions.anopheles."+aDescName);
                            return false;
                        }
                    }
                } else {
                    System.err.println("Unexpected element interventions.descriptions."+descName);
                    return false;
                }
            }
            interventions.removeChild(descriptions);
            Element continuous = getChildElement(interventions,"continuous");
            if (continuous != null) {
                NodeList cont = continuous.getChildNodes();
                l = cont.getLength();
                for ( int i = 0; i < l; ++i ) {
                    if (!(cont.item(i) instanceof Element)) continue;
                    Element oldElt = (Element) cont.item(i);
                    Element newElt = null;
                    String contName = oldElt.getNodeName();
                    if (contName.equals("ipti")) {
                        newElt = getOrCreateSubElt(interventions,"IPT");
                        newElt.appendChild(oldElt);
                        scenarioDocument.renameNode(oldElt,null,"continuous");
                    } else if (contName.equals("vaccine")) {
                        newElt = getOrCreateSubElt(interventions,"vaccine");
                        newElt.appendChild(oldElt);
                        scenarioDocument.renameNode(oldElt,null,"continuous");
                    } else if (contName.equals("cohort")) {
                        newElt = getOrCreateSubElt(interventions,"cohort");
                        newElt.appendChild(oldElt);
                        scenarioDocument.renameNode(oldElt,null,"continuous");
                    } else if (contName.equals("ITN")) {
                        newElt = getOrCreateSubElt(interventions,"ITN");
                        newElt.appendChild(oldElt);
                        scenarioDocument.renameNode(oldElt,null,"continuous");
                    } else {
                        System.err.println("Unexpected element interventions.continuous."+contName);
                        return false;
                    }
                }
                interventions.removeChild(continuous);
            }
            boolean givenIIWarning = false;
            Element timed = getChildElement(interventions,"timed");
            if (timed != null) {
                List<Node> times = getChildNodes(timed,"intervention");
                for ( Node timeNode : times ) {
                    Element timeElt = (Element) timeNode;
                    String intervTime = timeElt.getAttribute("time");

                    NodeList timedIntervs = timeElt.getChildNodes();
                    l = timedIntervs.getLength();
                    for ( int i=0; i<l; ++i ) {
                        if (!(timedIntervs.item(i) instanceof Element)) continue;
                        Element oldElt = (Element) timedIntervs.item(i);
                        String interv = oldElt.getNodeName();
                        Element newElt = null;
                        if (interv.equals("changeHS")) {
                            newElt = getOrCreateSubElt(interventions,"changeHS");
                            newElt.appendChild(oldElt);
                            scenarioDocument.renameNode(oldElt,null,"timed");
                            oldElt.setAttribute("time",intervTime);
                        } else if (interv.equals("changeEIR")) {
                            newElt = getOrCreateSubElt(interventions,"changeEIR");
                            newElt.appendChild(oldElt);
                            scenarioDocument.renameNode(oldElt,null,"timed");
                            oldElt.setAttribute("time",intervTime);
                        } else if (interv.equals("MDA")) {
                            newElt = getOrCreateSubElt(interventions,"MDA");
                            newElt.appendChild(oldElt);
                            scenarioDocument.renameNode(oldElt,null,"timed");
                            oldElt.setAttribute("time",intervTime);
                        } else if (interv.equals("vaccinate")) {
                            newElt = getOrCreateSubElt(interventions,"vaccine");
                            newElt.appendChild(oldElt);
                            scenarioDocument.renameNode(oldElt,null,"timed");
                            oldElt.setAttribute("time",intervTime);
                        } else if (interv.equals("ITN")) {
                            newElt = getOrCreateSubElt(interventions,"ITN");
                            newElt.appendChild(oldElt);
                            scenarioDocument.renameNode(oldElt,null,"timed");
                            oldElt.setAttribute("time",intervTime);
                        } else if (interv.equals("IRS")) {
                            newElt = getOrCreateSubElt(interventions,"IRS");
                            newElt.appendChild(oldElt);
                            scenarioDocument.renameNode(oldElt,null,"timed");
                            oldElt.setAttribute("time",intervTime);
                        } else if (interv.equals("VectorAvailability")) {
                            newElt = getOrCreateSubElt(interventions,"vectorDeterrent");
                            newElt.appendChild(oldElt);
                            scenarioDocument.renameNode(oldElt,null,"timed");
                            oldElt.setAttribute("time",intervTime);
                        } else if (interv.equals("ipti")) {
                            newElt = getOrCreateSubElt(interventions,"IPT");
                            newElt.appendChild(oldElt);
                            scenarioDocument.renameNode(oldElt,null,"timed");
                            oldElt.setAttribute("time",intervTime);
                        } else if (interv.equals("cohort")) {
                            newElt = getOrCreateSubElt(interventions,"cohort");
                            newElt.appendChild(oldElt);
                            scenarioDocument.renameNode(oldElt,null,"timed");
                            oldElt.setAttribute("time",intervTime);
                        } else if (interv.equals("uninfectVectors")) {
                            newElt = getOrCreateSubElt(interventions,"uninfectVectors");
                            newElt.appendChild(oldElt);
                            scenarioDocument.renameNode(oldElt,null,"timed");
                            oldElt.setAttribute("time",intervTime);
                        } else if (interv.equals("immuneSuppression")) {
                            newElt = getOrCreateSubElt(interventions,"immuneSuppression");
                            newElt.appendChild(oldElt);
                            scenarioDocument.renameNode(oldElt,null,"timed");
                            oldElt.setAttribute("time",intervTime);
                        } else if (interv.equals("insertR_0Case")) {
                            newElt = getOrCreateSubElt(interventions,"insertR_0Case");
                            newElt.appendChild(oldElt);
                            scenarioDocument.renameNode(oldElt,null,"timed");
                            oldElt.setAttribute("time",intervTime);
                        } else if (interv.equals("larviciding")) {
                            System.err.println("larviciding intervention model has changed significantly; please remove and re-add");
                            return false;
                        } else if (interv.equals("importedInfectionsPerThousandHosts")) {
                            if ( !givenIIWarning ) {
                                System.err.println("Warning: doing an exact conversion from old imported infections representation to new. This is probably not what you want unless you need to replicate results.");
                                givenIIWarning = true;
                            }
                            newElt = getOrCreateSubElt(interventions,"importedInfections");

                            Element timedII=getOrCreateSubElt(newElt,"timed");
                            List<Node> prevII=getChildNodes(timedII,"rate");
                            Element IINow;
                            if (prevII.size()>0 && Integer.parseInt(((Element)prevII.get(prevII.size()-1)).getAttribute("time"))==Integer.parseInt(intervTime)) {
                                IINow=(Element)prevII.get(prevII.size()-1);     // previous value, presumably an added zero, at same time point
                            } else {
                                IINow=scenarioDocument.createElement("rate");
                                timedII.appendChild(IINow);
                                IINow.setAttribute("time",intervTime);
                            }
                            IINow.setAttribute("value",Double.toString(Double.parseDouble(oldElt.getTextContent())*getStepsPerYear()));
                            // Now set the rate back to zero next time step, since this is what the old model did
                            Element IINext = scenarioDocument.createElement("rate");
                            timedII.appendChild(IINext);
                            IINext.setAttribute("time",Integer.toString(Integer.parseInt(intervTime)+1));
                            IINext.setAttribute("value","0");
                        } else {
                            System.err.println("Unexpected element interventions.timed.intervention."+interv);
                            return false;
                        }
                    }
                }
                interventions.removeChild(timed);
            }
            return true;
        } catch (DocException e) {
            System.err.println("Error: "+e.getMessage());
            return false;
        }
    }

    private double getStepsPerYear() throws DocException{
        return 365.0 / Integer.parseInt(getChildElement(getChildElement(scenarioElement,"model"),"parameters").getAttribute("interval"));
    }

    /* ITN description changed. While Oliver has drawn up plans for how to
     * replicate output of the old model, we feel that most of the time people
     * would rather use the new parameterisation, so we replace the old one.
     */
    public Boolean translate28To29() {
        try{
            Element interventions = getChildElement(scenarioElement,"interventions");
            Element ITNDesc = getChildElement(interventions,"ITN");
            if( ITNDesc != null ){
                if( ITN29Translation == ITN29ParameterTranslation.none ){
                    System.err.println("Error: ITN description changed. Pass argument --ITN-description to replace or ignore (see help)");
                    return false;
                }else if( ITN29Translation == ITN29ParameterTranslation.manual ){
                    System.err.println("Warning: leaving ITN description unchanged as requested");
                }else{
                    assert ITN29Translation == ITN29ParameterTranslation.replace;
                    
                    ITNDesc.removeChild(getChildElement(ITNDesc,"decay"));
                    Node anoph;
                    while((anoph=ITNDesc.getElementsByTagName("anophelesParams").item(0)) != null){
                        ITNDesc.removeChild(anoph);
                    }
                    Element desc = scenarioDocument.createElement("description");
                    ITNDesc.insertBefore(desc,ITNDesc.getFirstChild());
                    
                    Element usage = scenarioDocument.createElement("usage");
                    usage.setAttribute("value","0.8");
                    desc.appendChild(usage);
                    
                    Element holeRate = scenarioDocument.createElement("holeRate");
                    holeRate.setAttribute("mean","0.9");
                    holeRate.setAttribute("sigma","0.8");
                    desc.appendChild(holeRate);
                    
                    Element ripRate = scenarioDocument.createElement("ripRate");
                    ripRate.setAttribute("mean","0.7");
                    ripRate.setAttribute("sigma","0.8");
                    desc.appendChild(ripRate);
                    
                    Element ripFactor = scenarioDocument.createElement("ripFactor");
                    ripFactor.setAttribute("value","0.4");
                    desc.appendChild(ripFactor);
                    
                    Element initialInsecticide = scenarioDocument.createElement("initialInsecticide");
                    initialInsecticide.setAttribute("mu","70");
                    initialInsecticide.setAttribute("sigma","20");
                    desc.appendChild(initialInsecticide);
                    
                    Element insecticideDecay = scenarioDocument.createElement("insecticideDecay");
                    insecticideDecay.setAttribute("L","2.2");
                    insecticideDecay.setAttribute("function","exponential");
                    insecticideDecay.setAttribute("mu","-0.32");
                    insecticideDecay.setAttribute("sigma","0.8");
                    desc.appendChild(insecticideDecay);
                    
                    Element attritionOfNets = scenarioDocument.createElement("attritionOfNets");
                    attritionOfNets.setAttribute("L","12");
                    attritionOfNets.setAttribute("k","2");
                    attritionOfNets.setAttribute("function","smooth-compact");
                    desc.appendChild(attritionOfNets);
                    
                    Element anophParams = scenarioDocument.createElement("anophelesParams");
                    anophParams.setAttribute("mosquito","gambiae_ss");
                    Element deterrency = scenarioDocument.createElement("deterrency");
                    deterrency.setAttribute("holeFactor","0.5");
                    deterrency.setAttribute("insecticideFactor","0.67");
                    deterrency.setAttribute("interactionFactor","1.492537");
                    deterrency.setAttribute("holeScalingFactor","0.1");
                    deterrency.setAttribute("insecticideScalingFactor","0.1");
                    anophParams.appendChild(deterrency);
                    Element preprandialKillingEffect = scenarioDocument.createElement("preprandialKillingEffect");
                    preprandialKillingEffect.setAttribute("baseFactor","0.09");
                    preprandialKillingEffect.setAttribute("holeFactor","0.57");
                    preprandialKillingEffect.setAttribute("insecticideFactor","0.604");
                    preprandialKillingEffect.setAttribute("interactionFactor","-0.424");
                    preprandialKillingEffect.setAttribute("holeScalingFactor","0.1");
                    preprandialKillingEffect.setAttribute("insecticideScalingFactor","1");
                    anophParams.appendChild(preprandialKillingEffect);
                    Element postprandialKillingEffect = scenarioDocument.createElement("postprandialKillingEffect");
                    postprandialKillingEffect.setAttribute("baseFactor","0.10");
                    postprandialKillingEffect.setAttribute("holeFactor","0");
                    postprandialKillingEffect.setAttribute("insecticideFactor","0.55");
                    postprandialKillingEffect.setAttribute("interactionFactor","0");
                    postprandialKillingEffect.setAttribute("holeScalingFactor","0.1");
                    postprandialKillingEffect.setAttribute("insecticideScalingFactor","0.1");
                    anophParams.appendChild(postprandialKillingEffect);
                    desc.appendChild(anophParams.cloneNode(true));
                    
                    anophParams.setAttribute("mosquito","funestus");
                    desc.appendChild(anophParams.cloneNode(true));
                    
                    anophParams.setAttribute("mosquito","arabiensis");
                    deterrency.setAttribute("insecticideFactor","0.1");
                    preprandialKillingEffect.setAttribute("insecticideScalingFactor","0.1");
                    desc.appendChild(anophParams);
                }
            }
            return true;
        } catch (DocException e) {
            System.err.println("Error: "+e.getMessage());
            return false;
        }
    }
    
    /* Several vector attributes changed to elements.
     * entomology mode attribute now takes keyword enumerations
     * entomology annualEIR attribute was renamed
     * EIR survey measures changed name
     * EIR as Fourier coefficients can now use any odd number of coefficients.
     * Added mosquito life-cycle parameters (optional, so not added here)
     * timed elt of changeHS and changeEIR renamed to timedDeployment
     * new IRS model: choice of old or new parameters
     */
    public Boolean translate29To30() {
        try{
            Element mon = getChildElement(scenarioElement, "monitoring");
            Element survOpt = getChildElement(mon, "SurveyOptions");
            List<Node> opts = getChildNodes(survOpt, "option");
            for( Node optNode : opts ){
                Element opt = (Element) optNode;
                if( opt.getAttribute("name").equals("Vector_EIR_Input") ){
                    opt.setAttribute("name","inputEIR");
                }else if( opt.getAttribute("name").equals("Vector_EIR_Simulated") ){
                    opt.setAttribute("name","simulatedEIR");
                }
            }
            
            Element ento = getChildElement(scenarioElement, "entomology");
            int mode = Integer.parseInt(ento.getAttribute("mode"));
            if(mode==2){
                ento.setAttribute("mode","forced");
            }else{
                assert mode==4;
                ento.setAttribute("mode","dynamic");
            }
            if( ento.getAttributeNode("annualEIR") != null ){
                ento.setAttribute("scaledAnnualEIR", ento.getAttribute("annualEIR") );
                ento.removeAttribute("annualEIR");
            }
            
            Element vector = getChildElement(ento,"vector");
            if(vector != null){
                List<Node> anophs = getChildNodes(vector,"anopheles");
                for( Node anophNode : anophs ){
                    Element anoph = (Element)anophNode;
                    assert anoph != null;
                    Element mosq = getChildElement(anoph,"mosq");
                    convertAttrToElement(mosq, "mosqRestDuration");
                    convertAttrToElement(mosq, "extrinsicIncubationPeriod");
                    convertAttrToElement(mosq, "mosqLaidEggsSameDayProportion");
                    convertAttrToElement(mosq, "mosqSeekingDuration");
                    convertAttrToElement(mosq, "mosqSurvivalFeedingCycleProbability");
                    Element elt = scenarioDocument.createElement("availabilityVariance");
                    elt.setAttribute("value","0");
                    mosq.appendChild(elt);
                    convertAttrToBetaMeanElt(mosq, "mosqProbBiting");
                    convertAttrToBetaMeanElt(mosq, "mosqProbFindRestSite");
                    convertAttrToBetaMeanElt(mosq, "mosqProbResting");
                    convertAttrToElement(mosq, "mosqProbOvipositing");
                    convertAttrToElement(mosq, "mosqHumanBloodIndex");
                    
                    List<Node> nhhs = getChildNodes(anoph,"nonHumanHosts");
                    for( Node n : nhhs ){
                        Element nhh = (Element)n;
                        assert n!=null;
                        convertAttrToElement(nhh, "mosqRelativeEntoAvailability");
                        convertAttrToElement(nhh, "mosqProbBiting");
                        convertAttrToElement(nhh, "mosqProbFindRestSite");
                        convertAttrToElement(nhh, "mosqProbResting");
                    }
                    
                    Element seas = scenarioDocument.createElement("seasonality");
                    seas.setAttribute("input","EIR");
                    anoph.insertBefore(seas, anoph.getFirstChild());
                    
                    Element eir = getChildElement(anoph,"EIR");
                    if( eir != null ){
                        Element fS = scenarioDocument.createElement("fourierSeries");
                        seas.appendChild(fS);
                        
                        Element c1 = scenarioDocument.createElement("coeffic");
                        c1.setAttribute("a",eir.getAttribute("a1"));
                        c1.setAttribute("b",eir.getAttribute("b1"));
                        fS.appendChild( c1 );
                        Element c2 = scenarioDocument.createElement("coeffic");
                        c2.setAttribute("a",eir.getAttribute("a2"));
                        c2.setAttribute("b",eir.getAttribute("b2"));
                        fS.appendChild( c2 );
                        
                        fS.setAttribute("EIRRotateAngle", eir.getAttribute("EIRRotateAngle"));
                        
                        // calculate EIR so we can set annualEIR
                        // code is basically copied from VectorAnopheles::calcFourierEIR(...)
                        double annualEIR = 0.0;
                        double a0 = Double.parseDouble(eir.getAttribute("a0"));
                        double a1 = Double.parseDouble(eir.getAttribute("a1"));
                        double b1 = Double.parseDouble(eir.getAttribute("b1"));
                        double a2 = Double.parseDouble(eir.getAttribute("a2"));
                        double b2 = Double.parseDouble(eir.getAttribute("b2"));
                        double rAngle = Double.parseDouble(fS.getAttribute("EIRRotateAngle"));
                        double w = 2*Math.PI / 365;

                        for (int t=0; t<365; t++) {
                            double temp = a0;
                            double wt = w*t - rAngle;
                            temp += a1*Math.cos(wt) + b1*Math.sin(wt);
                            temp += a2*Math.cos(2*wt) + b2*Math.sin(2*wt);
                            annualEIR += Math.exp(temp);
                        }
                        seas.setAttribute("annualEIR",Double.toString(annualEIR));
                        
                        anoph.removeChild( eir );
                    }
                    
                    Element mE = getChildElement(anoph,"monthlyEIR");
                    if( mE != null ){
                        Element mV = scenarioDocument.createElement("monthlyValues");
                        seas.appendChild(mV);
                        seas.setAttribute("annualEIR", mE.getAttribute("annualEIR"));
                        mV.setAttribute("smoothing","fourier");
                        
                        List<Node> items = getChildNodes(mE, "item");
                        for(int i=0; i<items.size(); ++i){
                            Element v = scenarioDocument.createElement("value");
                            v.setTextContent(items.get(i).getTextContent());
                            mV.appendChild(v);
                        }
                        
                        anoph.removeChild(mE);
                    }
                }
            }
            
            Element intervs = getChildElement(scenarioElement,"interventions");
            Element interv = getChildElement(intervs,"changeHS");
            if(interv != null){
                for( Node n : getChildNodes(interv,"timed") )
                    scenarioDocument.renameNode( n, null, "timedDeployment" );
            }
            
            interv = getChildElement(intervs,"changeEIR");
            if(interv != null){
                for( Node n : getChildNodes(interv,"timed") )
                    scenarioDocument.renameNode( n, null, "timedDeployment" );
            }
            
            interv = getChildElement(intervs,"MDA");
            if( interv != null && interv.getElementsByTagName("timed").getLength() > 0 ){
                Element list  = scenarioDocument.createElement("timed");
                for( Node n : getChildNodes(interv, "timed") ){
                    list.appendChild(n);
                    scenarioDocument.renameNode(n,null,"deploy");
                }
                interv.appendChild(list);
            }
            
            interv = getChildElement(intervs,"vaccine");
            if( interv != null && interv.getElementsByTagName("continuous").getLength() > 0 ){
                Element list  = scenarioDocument.createElement("continuous");
                for( Node n : getChildNodes(interv, "continuous") ){
                    list.appendChild(n);
                    scenarioDocument.renameNode(n,null,"deploy");
                }
                interv.appendChild(list);
            }
            if( interv != null && interv.getElementsByTagName("timed").getLength() > 0 ){
                Element list  = scenarioDocument.createElement("timed");
                for( Node n : getChildNodes(interv, "timed") ){
                    list.appendChild(n);
                    scenarioDocument.renameNode(n,null,"deploy");
                }
                interv.appendChild(list);
            }
            
            interv = getChildElement(intervs,"IPT");
            if( interv != null && interv.getElementsByTagName("continuous").getLength() > 0 ){
                Element list  = scenarioDocument.createElement("continuous");
                for( Node n : getChildNodes(interv, "continuous") ){
                    list.appendChild(n);
                    scenarioDocument.renameNode(n,null,"deploy");
                }
                interv.appendChild(list);
            }
            if( interv != null && interv.getElementsByTagName("timed").getLength() > 0 ){
                Element list  = scenarioDocument.createElement("timed");
                for( Node n : getChildNodes(interv, "timed") ){
                    list.appendChild(n);
                    scenarioDocument.renameNode(n,null,"deploy");
                }
                interv.appendChild(list);
            }
            
            interv = getChildElement(intervs,"ITN");
            if( interv != null && interv.getElementsByTagName("continuous").getLength() > 0 ){
                Element list  = scenarioDocument.createElement("continuous");
                for( Node n : getChildNodes(interv, "continuous") ){
                    list.appendChild(n);
                    scenarioDocument.renameNode(n,null,"deploy");
                }
                interv.appendChild(list);
            }
            if( interv != null && interv.getElementsByTagName("timed").getLength() > 0 ){
                Element list  = scenarioDocument.createElement("timed");
                for( Node n : getChildNodes(interv, "timed") ){
                    list.appendChild(n);
                    scenarioDocument.renameNode(n,null,"deploy");
                }
                interv.appendChild(list);
            }
            
            interv = getChildElement(intervs,"IRS");
            if( interv != null ){
                if( /*TODO: use old or replace?*/ true ){
                    Element desc = scenarioDocument.createElement("description");
                    desc.appendChild(getChildElement(interv,"decay"));
                    for( Node n : getChildNodes(interv, "anophelesParams") ){
                        Element e = (Element) n;
                        assert( e != null );
                        // leave "deterrency" alone, rename "killingEffect" and add pre-prandial:
                        Element preprandial = scenarioDocument.createElement("preprandialKillingEffect");
                        preprandial.setAttribute("value","0");
                        Element postprandial = getChildElement(e, "killingEffect");
                        e.insertBefore(preprandial, postprandial);
                        scenarioDocument.renameNode(postprandial,null,"postprandialKillingEffect");
                        // move under "description":
                        desc.appendChild(n);
                    }
                    interv.appendChild(desc);
                }
            }
            if( interv != null && interv.getElementsByTagName("timed").getLength() > 0 ){
                Element list  = scenarioDocument.createElement("timed");
                for( Node n : getChildNodes(interv, "timed") ){
                    list.appendChild(n);
                    scenarioDocument.renameNode(n,null,"deploy");
                }
                interv.appendChild(list);
            }
            
            interv = getChildElement(intervs,"vectorDeterrent");
            if( interv != null && interv.getElementsByTagName("timed").getLength() > 0 ){
                Element list  = scenarioDocument.createElement("timed");
                for( Node n : getChildNodes(interv, "timed") ){
                    list.appendChild(n);
                    scenarioDocument.renameNode(n,null,"deploy");
                }
                interv.appendChild(list);
            }
            
            interv = getChildElement(intervs,"cohort");
            if( interv != null && interv.getElementsByTagName("continuous").getLength() > 0 ){
                Element list  = scenarioDocument.createElement("continuous");
                for( Node n : getChildNodes(interv, "continuous") ){
                    list.appendChild(n);
                    scenarioDocument.renameNode(n,null,"deploy");
                }
                interv.appendChild(list);
            }
            if( interv != null && interv.getElementsByTagName("timed").getLength() > 0 ){
                Element list  = scenarioDocument.createElement("timed");
                for( Node n : getChildNodes(interv, "timed") ){
                    list.appendChild(n);
                    scenarioDocument.renameNode(n,null,"deploy");
                }
                interv.appendChild(list);
            }
            
            interv = getChildElement(intervs,"immuneSuppression");
            if( interv != null && interv.getElementsByTagName("timed").getLength() > 0 ){
                Element list  = scenarioDocument.createElement("timed");
                for( Node n : getChildNodes(interv, "timed") ){
                    list.appendChild(n);
                    scenarioDocument.renameNode(n,null,"deploy");
                }
                interv.appendChild(list);
            }
            
            interv = getChildElement(intervs,"insertR_0Case");
            if(interv != null){
                for( Node n : getChildNodes(interv,"timed") )
                    scenarioDocument.renameNode( n, null, "timedDeployment" );
            }
            
            interv = getChildElement(intervs,"uninfectVectors");
            if(interv != null){
                for( Node n : getChildNodes(interv,"timed") )
                    scenarioDocument.renameNode( n, null, "timedDeployment" );
            }
            
            return true;
        } catch (DocException e) {
            System.err.println("Error: "+e.getMessage());
            return false;
        }
    }
    void convertAttrToElement(Element parent, String name){
        Element elt = scenarioDocument.createElement(name);
        elt.setAttribute("value",parent.getAttribute(name));
        parent.removeAttribute(name);
        parent.appendChild(elt);
    }
    void convertAttrToBetaMeanElt(Element parent, String name){
        Element elt = scenarioDocument.createElement(name);
        elt.setAttribute("mean",parent.getAttribute(name));
        elt.setAttribute("variance","0");
        parent.removeAttribute(name);
        parent.appendChild(elt);
    }
    
    /* Changes for Schema 31:
    * 
    * INNATE_MAX_DENS now defaults to true, but old scenarios have this explicitly set to false if not previously set to avoid changing results.
    * Larviciding -> vectorPop generic intervention
    */
    public Boolean translate30To31() {
        try{
            Element model = getChildElement(scenarioElement, "model");
            Element modelOpts = getChildElement(model, "ModelOptions");
            if( !containsOption( modelOpts, "INNATE_MAX_DENS") ){
                Element maxDensOpt = scenarioDocument.createElement("option");
                maxDensOpt.setAttribute("name","INNATE_MAX_DENS");
                maxDensOpt.setAttribute("value","false");
                modelOpts.appendChild(maxDensOpt);
            }

            Element intervs = getChildElement(scenarioElement, "interventions");
            if( intervs == null ) return true;
            Element larv = getChildElement( intervs, "larviciding" );
            if( larv != null ){
                // create new "vectorPop" parent
                Element vectorPop = scenarioDocument.createElement( "vectorPop" );
                intervs.appendChild( vectorPop );
                vectorPop.appendChild( larv );
                // set "name" attribute to name of larv elt if present otherwise insert a name
                Attr oldName = larv.getAttributeNode( "name" );
                String name = (oldName == null ? "simple larviciding" : oldName.getValue()) + " translated from schema 30";
                larv.setAttribute( "name", name );
                // update desc
                Element desc = getChildElement( larv, "description" );
                List<Node> anophs = getChildNodes( desc, "anopheles" );
                for( Node anoph : anophs ){
                    Element elt = (Element) anoph;
                    Element dur = getChildElement( elt, "duration" );
                    Element eff = getChildElement( elt, "effectiveness" );
                    String durStr = dur.getAttribute( "value" );
                    String effStr = eff.getAttribute( "value" );
                    elt.removeChild( dur );
                    elt.removeChild( eff );
                    Element er = scenarioDocument.createElement( "emergenceReduction" );
                    elt.appendChild( er );
                    er.setAttribute( "initial", effStr );
                    Element decay = scenarioDocument.createElement( "decay" );
                    er.appendChild( decay );
                    decay.setAttribute( "function", "step" );
                    double durDbl = Double.parseDouble( durStr ) / getStepsPerYear();
                    decay.setAttribute( "L", Double.toString( durDbl ) );
                }
                scenarioDocument.renameNode( larv, "", "intervention" );
            }
            return true;
        } catch (DocException e) {
            System.err.println("Error: "+e.getMessage());
            return false;
        }
    }
    private boolean containsOption( Element options, String name ){
        List<Node> opts = getChildNodes(options, "option");
        for( Node optNode : opts ){
            Element opt = (Element) optNode;
            if( opt.getAttribute("name").equals(name) ){
                return true;
            }
        }
        return false;
    }
    
    /**
     * This function is used to translate the 5-day timestep fitting
     * scenarii to 1-day timestep fitting scenarii. Since we're using a fairly
     * simple case management description (no interventions, no treatment),
     * then it's not too difficult to translate those scenarii. This translation
     * is therefore not intended for more complicated 5-day timestep scenarii.
     * @return true if the translation was a success.
     *
     */
    private Boolean oDTTranslation() throws Exception {
        Element surveys = (Element)scenarioElement.getElementsByTagName("surveys").item(0);
        NodeList surveystimes = surveys.getElementsByTagName("surveyTime");

        for (int i=0;i<surveystimes.getLength();i++)
        {
            Element surveytime = (Element)surveystimes.item(i);
            int surveyTime = Integer.parseInt(surveytime.getTextContent());
            surveytime.setTextContent(String.valueOf(((surveyTime -1)*5)+1));
        }

        Element modelElement = getChildElement(scenarioElement, "model");
        Element modelOptions = getChildElement(modelElement, "ModelOptions");

        Element molineauxOption = scenarioDocument.createElement("option");
        Attr name = scenarioDocument.createAttribute("name");
        name.setNodeValue("MOLINEAUX_WITHIN_HOST_MODEL");
        Attr value = scenarioDocument.createAttribute("value");
        value.setNodeValue("true");
        molineauxOption.setAttributeNode(name);
        molineauxOption.setAttributeNode(value);
        modelOptions.appendChild(molineauxOption);

        Element pkpdOption = scenarioDocument.createElement("option");
        Attr namePkPd = scenarioDocument.createAttribute("name");
        namePkPd.setNodeValue("INCLUDES_PK_PD");
        Attr valuePkPd = scenarioDocument.createAttribute("value");
        valuePkPd.setNodeValue("true");
        pkpdOption.setAttributeNode(namePkPd);
        pkpdOption.setAttributeNode(valuePkPd);
        modelOptions.appendChild(pkpdOption);

        Element esOption = scenarioDocument.createElement("option");
        Attr nameES = scenarioDocument.createAttribute("name");
        nameES.setNodeValue("CLINICAL_EVENT_SCHEDULER");
        Attr valueES = scenarioDocument.createAttribute("value");
        valueES.setNodeValue("true");
        esOption.setAttributeNode(nameES);
        esOption.setAttributeNode(valueES);
        modelOptions.appendChild(esOption);

        Element clinical = (Element)scenarioElement.getElementsByTagName("clinical").item(0);
        Attr healthSystemMemory = clinical.getAttributeNode("healthSystemMemory");
        healthSystemMemory.setValue(String.valueOf(28));

        NodeList interventionList = scenarioElement.getElementsByTagName("intervention");

        for (int i=0;i<interventionList.getLength();i++)
        {
            Element intervention = (Element)interventionList.item(0);
            Attr time = intervention.getAttributeNode("time");

            if (time!=null)
                time.setNodeValue(String.valueOf(((Integer.parseInt(time.getNodeValue())-1)*5)+1));
        }

        NodeList changeHSList = scenarioElement.getElementsByTagName("changeHS");

        for (int i=0;i<changeHSList.getLength();i++)
        {
            Element changeHS = (Element) changeHSList.item(i);
            Element immediateOutcomes = getChildElement(changeHS, "ImmediateOutcomes");
            String valueString = immediateOutcomes.getAttribute("name");

            if (valueString.equals("Do Monitoring HS")||valueString.equals("Np Monitoring HS"))
            {
                Element eventScheduler = scenarioDocument.createElement("EventScheduler");
                changeHS.removeChild(immediateOutcomes);

                Element uncomplicated = scenarioDocument.createElement("uncomplicated");
                Element decisions = scenarioDocument.createElement("decisions");

                Element decisionTreat = scenarioDocument.createElement("decision");

                Attr nameTreat = scenarioDocument.createAttribute("name");
                nameTreat.setNodeValue("treatment");
                Attr dependsTreat = scenarioDocument.createAttribute("depends");
                dependsTreat.setNodeValue("");
                Attr valuesTreat = scenarioDocument.createAttribute("values");
                valuesTreat.setNodeValue("effective_treat,none");

                decisionTreat.setAttributeNode(nameTreat);
                decisionTreat.setAttributeNode(dependsTreat);
                decisionTreat.setAttributeNode(valuesTreat);
                decisionTreat.setTextContent("effective_treat");
                decisions.appendChild(decisionTreat);

                Element decisionTest = scenarioDocument.createElement("decision");

                Attr nameTest = scenarioDocument.createAttribute("name");
                nameTest.setNodeValue("test");
                Attr dependsTest = scenarioDocument.createAttribute("depends");
                dependsTest.setNodeValue("");
                Attr valuesTest = scenarioDocument.createAttribute("values");
                valuesTest.setNodeValue("none,microscopy,RDT");

                decisionTest.setAttributeNode(nameTest);
                decisionTest.setAttributeNode(dependsTest);
                decisionTest.setAttributeNode(valuesTest);
                decisionTest.setTextContent("none");
                decisions.appendChild(decisionTest);

                uncomplicated.appendChild(decisions);

                Element treatments = scenarioDocument.createElement("treatments");
                Element treatment = scenarioDocument.createElement("treatment");
                Attr nameTreatEl = scenarioDocument.createAttribute("name");
                nameTreatEl.setNodeValue("effective_treat");
                treatment.setAttributeNode(nameTreatEl);

                Element schedule = scenarioDocument.createElement("schedule");
                Element medicate = scenarioDocument.createElement("medicate");

                Attr drug = scenarioDocument.createAttribute("drug");
                drug.setNodeValue("effective");
                Attr mg = scenarioDocument.createAttribute("mg");
                mg.setNodeValue("1");
                Attr hour = scenarioDocument.createAttribute("hour");
                hour.setNodeValue("0");

                medicate.setAttributeNode(drug);
                medicate.setAttributeNode(mg);
                medicate.setAttributeNode(hour);

                schedule.appendChild(medicate);
                treatment.appendChild(schedule);
                treatments.appendChild(treatment);

                Element treatmentNone = scenarioDocument.createElement("treatment");
                Attr nameTreatNone = scenarioDocument.createAttribute("name");
                nameTreatNone.setNodeValue("none");
                treatmentNone.setAttributeNode(nameTreatNone);

                Element scheduleNone = scenarioDocument.createElement("schedule");
                treatmentNone.appendChild(scheduleNone);
                treatments.appendChild(treatmentNone);
                uncomplicated.appendChild(treatments);

                eventScheduler.appendChild(uncomplicated);

                Element complicated = scenarioDocument.createElement("complicated");
                Element decisionsComp = scenarioDocument.createElement("decisions");

                Element decisionCompTreat = scenarioDocument.createElement("decision");

                Attr nameCompTreat = scenarioDocument.createAttribute("name");
                nameCompTreat.setNodeValue("treatment");
                Attr dependsCompTreat = scenarioDocument.createAttribute("depends");
                dependsCompTreat.setNodeValue("");
                Attr valuesCompTreat = scenarioDocument.createAttribute("values");
                valuesCompTreat.setNodeValue("effective_treat,none");

                decisionCompTreat.setAttributeNode(nameCompTreat);
                decisionCompTreat.setAttributeNode(dependsCompTreat);
                decisionCompTreat.setAttributeNode(valuesCompTreat);
                decisionCompTreat.setTextContent("effective_treat");
                decisionsComp.appendChild(decisionCompTreat);

                Element decisionCompHosp = scenarioDocument.createElement("decision");

                Attr nameCompHosp = scenarioDocument.createAttribute("name");
                nameCompHosp.setNodeValue("hospitalisation");
                Attr dependsCompHosp = scenarioDocument.createAttribute("depends");
                dependsCompHosp.setNodeValue("");
                Attr valuesCompHosp = scenarioDocument.createAttribute("values");
                valuesCompHosp.setNodeValue("none,delayed,immediate");

                decisionCompHosp.setAttributeNode(nameCompHosp);
                decisionCompHosp.setAttributeNode(dependsCompHosp);
                decisionCompHosp.setAttributeNode(valuesCompHosp);
                decisionCompHosp.setTextContent("immediate");
                decisionsComp.appendChild(decisionCompHosp);

                Element decisionCompTest = scenarioDocument.createElement("decision");

                Attr nameCompTest = scenarioDocument.createAttribute("name");
                nameCompTest.setNodeValue("test");
                Attr dependsCompTest = scenarioDocument.createAttribute("depends");
                dependsCompTest.setNodeValue("");
                Attr valuesCompTest = scenarioDocument.createAttribute("values");
                valuesCompTest.setNodeValue("none,microscopy,RDT");

                decisionCompTest.setAttributeNode(nameCompTest);
                decisionCompTest.setAttributeNode(dependsCompTest);
                decisionCompTest.setAttributeNode(valuesCompTest);
                decisionCompTest.setTextContent("none");
                decisionsComp.appendChild(decisionCompTest);

                complicated.appendChild(decisionsComp);

                Element treatmentsComp = scenarioDocument.createElement("treatments");
                Element treatmentComp = scenarioDocument.createElement("treatment");
                Attr nameTreatComp = scenarioDocument.createAttribute("name");
                nameTreatComp.setNodeValue("effective_treat");
                treatmentComp.setAttributeNode(nameTreatComp);

                Element scheduleComp = scenarioDocument.createElement("schedule");
                Element medicateComp = scenarioDocument.createElement("medicate");

                Attr drugComp = scenarioDocument.createAttribute("drug");
                drugComp.setNodeValue("effective");
                Attr mgComp = scenarioDocument.createAttribute("mg");
                mgComp.setNodeValue("1");
                Attr hourComp = scenarioDocument.createAttribute("hour");
                hourComp.setNodeValue("0");

                medicateComp.setAttributeNode(drugComp);
                medicateComp.setAttributeNode(mgComp);
                medicateComp.setAttributeNode(hourComp);

                scheduleComp.appendChild(medicateComp);
                treatmentComp.appendChild(scheduleComp);
                treatmentsComp.appendChild(treatmentComp);

                Element treatmentCompNone = scenarioDocument.createElement("treatment");
                Attr nameTreatCompNone = scenarioDocument.createAttribute("name");
                nameTreatCompNone.setNodeValue("none");
                treatmentCompNone.setAttributeNode(nameTreatCompNone);

                Element scheduleCompNone = scenarioDocument.createElement("schedule");
                treatmentCompNone.appendChild(scheduleCompNone);
                treatmentsComp.appendChild(treatmentCompNone);
                complicated.appendChild(treatmentsComp);

                eventScheduler.appendChild(complicated);

                Element clinicalOutcomes = scenarioDocument.createElement("ClinicalOutcomes");
                Element maxUCSeekingMemory = scenarioDocument.createElement("maxUCSeekingMemory");
                maxUCSeekingMemory.setTextContent("3");
                Element uncomplicatedCaseDuration = scenarioDocument.createElement("uncomplicatedCaseDuration");
                uncomplicatedCaseDuration.setTextContent("3");
                Element complicatedCaseDuration = scenarioDocument.createElement("complicatedCaseDuration");
                complicatedCaseDuration.setTextContent("5");
                Element complicatedRiskDuration = scenarioDocument.createElement("complicatedRiskDuration");
                complicatedRiskDuration.setTextContent("5");
                Element pImmediateUC = scenarioDocument.createElement("pImmediateUC");
                pImmediateUC.setTextContent("1");
                //Element propDeathsFirstDay = scenarioDocument.createElement("propDeathsFirstDay");
                //propDeathsFirstDay.setTextContent("0.4");

                //this communityOddsMultiplier will be removed (for schema >= 19)
                //Element communityOddsMultiplier = scenarioDocument.createElement("communityOddsMultiplier");
                //communityOddsMultiplier.setTextContent("1.5");

                clinicalOutcomes.appendChild(maxUCSeekingMemory);
                clinicalOutcomes.appendChild(uncomplicatedCaseDuration);
                clinicalOutcomes.appendChild(complicatedCaseDuration);
                clinicalOutcomes.appendChild(complicatedRiskDuration);
                clinicalOutcomes.appendChild(pImmediateUC);


                //clinicalOutcomes.appendChild(communityOddsMultiplier);

                eventScheduler.appendChild(clinicalOutcomes);


                Element CFR = getChildElement(changeHS, "CFR");
                if (CFR!=null)
                    changeHS.insertBefore(eventScheduler, CFR);
                else
                    changeHS.appendChild(eventScheduler);
            }
            else return false;
        }



        Element healthSystem = (Element)scenarioDocument.getElementsByTagName("healthSystem").item(0);

        Element immediateOutcomes = getChildElement(healthSystem, "ImmediateOutcomes");
        String valueString = immediateOutcomes.getAttribute("name");

        System.out.println(valueString);

        if (valueString.equals("no Treatment")||valueString.equals("Mortality Fitting")||valueString.equals("no Treatment no Mortality"))
        {
            Element eventScheduler = scenarioDocument.createElement("EventScheduler");
            healthSystem.removeChild(immediateOutcomes);

            Element uncomplicated = scenarioDocument.createElement("uncomplicated");
            Element decisions = scenarioDocument.createElement("decisions");

            Element decisionTreat = scenarioDocument.createElement("decision");

            Attr nameTreat = scenarioDocument.createAttribute("name");
            nameTreat.setNodeValue("treatment");
            Attr dependsTreat = scenarioDocument.createAttribute("depends");
            dependsTreat.setNodeValue("");
            Attr valuesTreat = scenarioDocument.createAttribute("values");
            valuesTreat.setNodeValue("effective_treat,none");

            decisionTreat.setAttributeNode(nameTreat);
            decisionTreat.setAttributeNode(dependsTreat);
            decisionTreat.setAttributeNode(valuesTreat);
            decisionTreat.setTextContent("none");
            decisions.appendChild(decisionTreat);

            Element decisionTest = scenarioDocument.createElement("decision");

            Attr nameTest = scenarioDocument.createAttribute("name");
            nameTest.setNodeValue("test");
            Attr dependsTest = scenarioDocument.createAttribute("depends");
            dependsTest.setNodeValue("");
            Attr valuesTest = scenarioDocument.createAttribute("values");
            valuesTest.setNodeValue("none,microscopy,RDT");

            decisionTest.setAttributeNode(nameTest);
            decisionTest.setAttributeNode(dependsTest);
            decisionTest.setAttributeNode(valuesTest);
            decisionTest.setTextContent("none");
            decisions.appendChild(decisionTest);

            uncomplicated.appendChild(decisions);

            Element treatments = scenarioDocument.createElement("treatments");
            Element treatment = scenarioDocument.createElement("treatment");
            Attr nameTreatEl = scenarioDocument.createAttribute("name");
            nameTreatEl.setNodeValue("effective_treat");
            treatment.setAttributeNode(nameTreatEl);

            Element schedule = scenarioDocument.createElement("schedule");
            Element medicate = scenarioDocument.createElement("medicate");

            Attr drug = scenarioDocument.createAttribute("drug");
            drug.setNodeValue("effective");
            Attr mg = scenarioDocument.createAttribute("mg");
            mg.setNodeValue("1");
            Attr hour = scenarioDocument.createAttribute("hour");
            hour.setNodeValue("0");

            medicate.setAttributeNode(drug);
            medicate.setAttributeNode(mg);
            medicate.setAttributeNode(hour);

            schedule.appendChild(medicate);
            treatment.appendChild(schedule);
            treatments.appendChild(treatment);

            Element treatmentNone = scenarioDocument.createElement("treatment");
            Attr nameTreatNone = scenarioDocument.createAttribute("name");
            nameTreatNone.setNodeValue("none");
            treatmentNone.setAttributeNode(nameTreatNone);

            Element scheduleNone = scenarioDocument.createElement("schedule");
            treatmentNone.appendChild(scheduleNone);
            treatments.appendChild(treatmentNone);
            uncomplicated.appendChild(treatments);

            eventScheduler.appendChild(uncomplicated);

            Element complicated = scenarioDocument.createElement("complicated");
            Element decisionsComp = scenarioDocument.createElement("decisions");

            Element decisionCompTreat = scenarioDocument.createElement("decision");

            Attr nameCompTreat = scenarioDocument.createAttribute("name");
            nameCompTreat.setNodeValue("treatment");
            Attr dependsCompTreat = scenarioDocument.createAttribute("depends");
            dependsCompTreat.setNodeValue("");
            Attr valuesCompTreat = scenarioDocument.createAttribute("values");
            valuesCompTreat.setNodeValue("effective_treat,none");

            decisionCompTreat.setAttributeNode(nameCompTreat);
            decisionCompTreat.setAttributeNode(dependsCompTreat);
            decisionCompTreat.setAttributeNode(valuesCompTreat);
            decisionCompTreat.setTextContent("none");
            decisionsComp.appendChild(decisionCompTreat);

            Element decisionCompHosp = scenarioDocument.createElement("decision");

            Attr nameCompHosp = scenarioDocument.createAttribute("name");
            nameCompHosp.setNodeValue("hospitalisation");
            Attr dependsCompHosp = scenarioDocument.createAttribute("depends");
            dependsCompHosp.setNodeValue("");
            Attr valuesCompHosp = scenarioDocument.createAttribute("values");
            valuesCompHosp.setNodeValue("none,delayed,immediate");

            decisionCompHosp.setAttributeNode(nameCompHosp);
            decisionCompHosp.setAttributeNode(dependsCompHosp);
            decisionCompHosp.setAttributeNode(valuesCompHosp);
            decisionCompHosp.setTextContent("none");
            decisionsComp.appendChild(decisionCompHosp);

            Element decisionCompTest = scenarioDocument.createElement("decision");

            Attr nameCompTest = scenarioDocument.createAttribute("name");
            nameCompTest.setNodeValue("test");
            Attr dependsCompTest = scenarioDocument.createAttribute("depends");
            dependsCompTest.setNodeValue("");
            Attr valuesCompTest = scenarioDocument.createAttribute("values");
            valuesCompTest.setNodeValue("none,microscopy,RDT");

            decisionCompTest.setAttributeNode(nameCompTest);
            decisionCompTest.setAttributeNode(dependsCompTest);
            decisionCompTest.setAttributeNode(valuesCompTest);
            decisionCompTest.setTextContent("none");
            decisionsComp.appendChild(decisionCompTest);

            complicated.appendChild(decisionsComp);

            Element treatmentsComp = scenarioDocument.createElement("treatments");
            Element treatmentComp = scenarioDocument.createElement("treatment");
            Attr nameTreatComp = scenarioDocument.createAttribute("name");
            nameTreatComp.setNodeValue("effective_treat");
            treatmentComp.setAttributeNode(nameTreatComp);

            Element scheduleComp = scenarioDocument.createElement("schedule");
            Element medicateComp = scenarioDocument.createElement("medicate");

            Attr drugComp = scenarioDocument.createAttribute("drug");
            drugComp.setNodeValue("effective");
            Attr mgComp = scenarioDocument.createAttribute("mg");
            mgComp.setNodeValue("1");
            Attr hourComp = scenarioDocument.createAttribute("hour");
            hourComp.setNodeValue("0");

            medicateComp.setAttributeNode(drugComp);
            medicateComp.setAttributeNode(mgComp);
            medicateComp.setAttributeNode(hourComp);

            scheduleComp.appendChild(medicateComp);
            treatmentComp.appendChild(scheduleComp);
            treatmentsComp.appendChild(treatmentComp);

            Element treatmentCompNone = scenarioDocument.createElement("treatment");
            Attr nameTreatCompNone = scenarioDocument.createAttribute("name");
            nameTreatCompNone.setNodeValue("none");
            treatmentCompNone.setAttributeNode(nameTreatCompNone);

            Element scheduleCompNone = scenarioDocument.createElement("schedule");
            treatmentCompNone.appendChild(scheduleCompNone);
            treatmentsComp.appendChild(treatmentCompNone);
            complicated.appendChild(treatmentsComp);

            eventScheduler.appendChild(complicated);

            Element clinicalOutcomes = scenarioDocument.createElement("ClinicalOutcomes");
            Element maxUCSeekingMemory = scenarioDocument.createElement("maxUCSeekingMemory");
            maxUCSeekingMemory.setTextContent("3");
            Element uncomplicatedCaseDuration = scenarioDocument.createElement("uncomplicatedCaseDuration");
            uncomplicatedCaseDuration.setTextContent("3");
            Element complicatedCaseDuration = scenarioDocument.createElement("complicatedCaseDuration");
            complicatedCaseDuration.setTextContent("5");
            Element complicatedRiskDuration = scenarioDocument.createElement("complicatedRiskDuration");
            complicatedRiskDuration.setTextContent("5");
            Element pImmediateUC = scenarioDocument.createElement("pImmediateUC");
            pImmediateUC.setTextContent("1");
            //Element propDeathsFirstDay = scenarioDocument.createElement("propDeathsFirstDay");
            //propDeathsFirstDay.setTextContent("0.4");

            //this communityOddsMultiplier will be removed (for schema >= 19)
            //Element communityOddsMultiplier = scenarioDocument.createElement("communityOddsMultiplier");
            //communityOddsMultiplier.setTextContent("1.5");

            clinicalOutcomes.appendChild(maxUCSeekingMemory);
            clinicalOutcomes.appendChild(uncomplicatedCaseDuration);
            clinicalOutcomes.appendChild(complicatedCaseDuration);
            clinicalOutcomes.appendChild(complicatedRiskDuration);
            clinicalOutcomes.appendChild(pImmediateUC);


            //clinicalOutcomes.appendChild(communityOddsMultiplier);

            eventScheduler.appendChild(clinicalOutcomes);


            Element CFR = getChildElement(healthSystem, "CFR");
            if (CFR!=null)
                healthSystem.insertBefore(eventScheduler, CFR);
            else
                healthSystem.appendChild(eventScheduler);
        }
        else if (valueString.equals("Ironmal"))
        {
            Element eventScheduler = scenarioDocument.createElement("EventScheduler");
            healthSystem.removeChild(immediateOutcomes);

            Element uncomplicated = scenarioDocument.createElement("uncomplicated");
            Element decisions = scenarioDocument.createElement("decisions");

            Element decisionOC = scenarioDocument.createElement("decision");

            Attr nameOC = scenarioDocument.createAttribute("name");
            nameOC.setNodeValue("official_care");
            Attr dependsOC = scenarioDocument.createAttribute("depends");
            dependsOC.setNodeValue("p");
            Attr valuesOC = scenarioDocument.createAttribute("values");
            valuesOC.setNodeValue("yes,no");

            decisionOC.setAttributeNode(nameOC);
            decisionOC.setAttributeNode(dependsOC);
            decisionOC.setAttributeNode(valuesOC);
            decisionOC.setTextContent("p(.64): yes p(.36): no");
            decisions.appendChild(decisionOC);

            Element decisionTreat = scenarioDocument.createElement("decision");

            Attr nameTreat = scenarioDocument.createAttribute("name");
            nameTreat.setNodeValue("treatment");
            Attr dependsTreat = scenarioDocument.createAttribute("depends");
            dependsTreat.setNodeValue("official_care,p");
            Attr valuesTreat = scenarioDocument.createAttribute("values");
            valuesTreat.setNodeValue("effective_treat,none");

            decisionTreat.setAttributeNode(nameTreat);
            decisionTreat.setAttributeNode(dependsTreat);
            decisionTreat.setAttributeNode(valuesTreat);
            decisionTreat.setTextContent("official_care(yes){p(.6): effective_treat p(.4): none} official_care(no): none");
            decisions.appendChild(decisionTreat);

            Element decisionTest = scenarioDocument.createElement("decision");

            Attr nameTest = scenarioDocument.createAttribute("name");
            nameTest.setNodeValue("test");
            Attr dependsTest = scenarioDocument.createAttribute("depends");
            dependsTest.setNodeValue("");
            Attr valuesTest = scenarioDocument.createAttribute("values");
            valuesTest.setNodeValue("none,microscopy,RDT");

            decisionTest.setAttributeNode(nameTest);
            decisionTest.setAttributeNode(dependsTest);
            decisionTest.setAttributeNode(valuesTest);
            decisionTest.setTextContent("none");
            decisions.appendChild(decisionTest);

            uncomplicated.appendChild(decisions);

            Element treatments = scenarioDocument.createElement("treatments");
            Element treatment = scenarioDocument.createElement("treatment");
            Attr nameTreatEl = scenarioDocument.createAttribute("name");
            nameTreatEl.setNodeValue("effective_treat");
            treatment.setAttributeNode(nameTreatEl);

            Element schedule = scenarioDocument.createElement("schedule");
            Element medicate = scenarioDocument.createElement("medicate");

            Attr drug = scenarioDocument.createAttribute("drug");
            drug.setNodeValue("effective");
            Attr mg = scenarioDocument.createAttribute("mg");
            mg.setNodeValue("1");
            Attr hour = scenarioDocument.createAttribute("hour");
            hour.setNodeValue("0");

            medicate.setAttributeNode(drug);
            medicate.setAttributeNode(mg);
            medicate.setAttributeNode(hour);

            schedule.appendChild(medicate);
            treatment.appendChild(schedule);
            treatments.appendChild(treatment);

            Element treatmentNone = scenarioDocument.createElement("treatment");
            Attr nameTreatNone = scenarioDocument.createAttribute("name");
            nameTreatNone.setNodeValue("none");
            treatmentNone.setAttributeNode(nameTreatNone);

            Element scheduleNone = scenarioDocument.createElement("schedule");
            treatmentNone.appendChild(scheduleNone);
            treatments.appendChild(treatmentNone);
            uncomplicated.appendChild(treatments);

            eventScheduler.appendChild(uncomplicated);

            Element complicated = scenarioDocument.createElement("complicated");
            Element decisionsComp = scenarioDocument.createElement("decisions");

            Element decisionOCComp = scenarioDocument.createElement("decision");

            Attr nameOCComp = scenarioDocument.createAttribute("name");
            nameOCComp.setNodeValue("official_care");
            Attr dependsOCComp = scenarioDocument.createAttribute("depends");
            dependsOCComp.setNodeValue("p");
            Attr valuesOCComp = scenarioDocument.createAttribute("values");
            valuesOCComp.setNodeValue("yes,no");

            decisionOCComp.setAttributeNode(nameOCComp);
            decisionOCComp.setAttributeNode(dependsOCComp);
            decisionOCComp.setAttributeNode(valuesOCComp);
            decisionOCComp.setTextContent("p(.48): yes p(.52): no");
            decisionsComp.appendChild(decisionOCComp);

            Element decisionCompTreat = scenarioDocument.createElement("decision");

            Attr nameCompTreat = scenarioDocument.createAttribute("name");
            nameCompTreat.setNodeValue("treatment");
            Attr dependsCompTreat = scenarioDocument.createAttribute("depends");
            dependsCompTreat.setNodeValue("official_care,p");
            Attr valuesCompTreat = scenarioDocument.createAttribute("values");
            valuesCompTreat.setNodeValue("effective_treat,none");

            decisionCompTreat.setAttributeNode(nameCompTreat);
            decisionCompTreat.setAttributeNode(dependsCompTreat);
            decisionCompTreat.setAttributeNode(valuesCompTreat);
            decisionCompTreat.setTextContent("official_care(yes){p(.6): effective_treat p(.4): none} official_care(no): none");
            decisionsComp.appendChild(decisionCompTreat);

            Element decisionCompHosp = scenarioDocument.createElement("decision");

            Attr nameCompHosp = scenarioDocument.createAttribute("name");
            nameCompHosp.setNodeValue("hospitalisation");
            Attr dependsCompHosp = scenarioDocument.createAttribute("depends");
            dependsCompHosp.setNodeValue("official_care");
            Attr valuesCompHosp = scenarioDocument.createAttribute("values");
            valuesCompHosp.setNodeValue("none,delayed,immediate");

            decisionCompHosp.setAttributeNode(nameCompHosp);
            decisionCompHosp.setAttributeNode(dependsCompHosp);
            decisionCompHosp.setAttributeNode(valuesCompHosp);
            decisionCompHosp.setTextContent("official_care(yes): immediate official_care(no): none");
            decisionsComp.appendChild(decisionCompHosp);

            Element decisionCompTest = scenarioDocument.createElement("decision");

            Attr nameCompTest = scenarioDocument.createAttribute("name");
            nameCompTest.setNodeValue("test");
            Attr dependsCompTest = scenarioDocument.createAttribute("depends");
            dependsCompTest.setNodeValue("");
            Attr valuesCompTest = scenarioDocument.createAttribute("values");
            valuesCompTest.setNodeValue("none,microscopy,RDT");

            decisionCompTest.setAttributeNode(nameCompTest);
            decisionCompTest.setAttributeNode(dependsCompTest);
            decisionCompTest.setAttributeNode(valuesCompTest);
            decisionCompTest.setTextContent("none");
            decisionsComp.appendChild(decisionCompTest);

            complicated.appendChild(decisionsComp);

            Element treatmentsComp = scenarioDocument.createElement("treatments");
            Element treatmentComp = scenarioDocument.createElement("treatment");
            Attr nameTreatComp = scenarioDocument.createAttribute("name");
            nameTreatComp.setNodeValue("effective_treat");
            treatmentComp.setAttributeNode(nameTreatComp);

            Element scheduleComp = scenarioDocument.createElement("schedule");
            Element medicateComp = scenarioDocument.createElement("medicate");

            Attr drugComp = scenarioDocument.createAttribute("drug");
            drugComp.setNodeValue("effective");
            Attr mgComp = scenarioDocument.createAttribute("mg");
            mgComp.setNodeValue("1");
            Attr hourComp = scenarioDocument.createAttribute("hour");
            hourComp.setNodeValue("0");

            medicateComp.setAttributeNode(drugComp);
            medicateComp.setAttributeNode(mgComp);
            medicateComp.setAttributeNode(hourComp);

            scheduleComp.appendChild(medicateComp);
            treatmentComp.appendChild(scheduleComp);
            treatmentsComp.appendChild(treatmentComp);

            Element treatmentCompNone = scenarioDocument.createElement("treatment");
            Attr nameTreatCompNone = scenarioDocument.createAttribute("name");
            nameTreatCompNone.setNodeValue("none");
            treatmentCompNone.setAttributeNode(nameTreatCompNone);

            Element scheduleCompNone = scenarioDocument.createElement("schedule");
            treatmentCompNone.appendChild(scheduleCompNone);
            treatmentsComp.appendChild(treatmentCompNone);
            complicated.appendChild(treatmentsComp);

            eventScheduler.appendChild(complicated);

            Element clinicalOutcomes = scenarioDocument.createElement("ClinicalOutcomes");
            Element maxUCSeekingMemory = scenarioDocument.createElement("maxUCSeekingMemory");
            maxUCSeekingMemory.setTextContent("3");
            Element uncomplicatedCaseDuration = scenarioDocument.createElement("uncomplicatedCaseDuration");
            uncomplicatedCaseDuration.setTextContent("3");
            Element complicatedCaseDuration = scenarioDocument.createElement("complicatedCaseDuration");
            complicatedCaseDuration.setTextContent("5");
            Element complicatedRiskDuration = scenarioDocument.createElement("complicatedRiskDuration");
            complicatedRiskDuration.setTextContent("5");
            Element pImmediateUC = scenarioDocument.createElement("pImmediateUC");
            pImmediateUC.setTextContent("1");
            //Element propDeathsFirstDay = scenarioDocument.createElement("propDeathsFirstDay");
            //propDeathsFirstDay.setTextContent("0.4");

            //this communityOddsMultiplier will be removed (for schema >= 19)
            //Element communityOddsMultiplier = scenarioDocument.createElement("communityOddsMultiplier");
            //communityOddsMultiplier.setTextContent("1.5");

            clinicalOutcomes.appendChild(maxUCSeekingMemory);
            clinicalOutcomes.appendChild(uncomplicatedCaseDuration);
            clinicalOutcomes.appendChild(complicatedCaseDuration);
            clinicalOutcomes.appendChild(complicatedRiskDuration);
            clinicalOutcomes.appendChild(pImmediateUC);


            //clinicalOutcomes.appendChild(communityOddsMultiplier);

            eventScheduler.appendChild(clinicalOutcomes);


            Element CFR = getChildElement(healthSystem, "CFR");
            if (CFR!=null)
                healthSystem.insertBefore(eventScheduler, CFR);
            else
                healthSystem.appendChild(eventScheduler);
        }
        else return false;

        // creating drugdescription element
        Element drugDescription = scenarioDocument.createElement("drugDescription");

        Element drug = scenarioDocument.createElement("drug");
        Attr abbrev = scenarioDocument.createAttribute("abbrev");
        abbrev.setNodeValue("effective");
        drug.setAttributeNode(abbrev);

        Element pd = scenarioDocument.createElement("PD");

        Element allele = scenarioDocument.createElement("allele");
        Attr nameAllele = scenarioDocument.createAttribute("name");
        nameAllele.setNodeValue("sensitive");
        allele.setAttributeNode(nameAllele);

        Element initial_frequency = scenarioDocument.createElement("initial_frequency");
        initial_frequency.setTextContent("1");

        Element max_killing_rate = scenarioDocument.createElement("max_killing_rate");
        max_killing_rate.setTextContent("1e7");

        Element ic50 = scenarioDocument.createElement("IC50");
        ic50.setTextContent("1");

        Element slope = scenarioDocument.createElement("slope");
        slope.setTextContent("1");

        allele.appendChild(initial_frequency);
        allele.appendChild(max_killing_rate);
        allele.appendChild(ic50);
        allele.appendChild(slope);

        pd.appendChild(allele);
        drug.appendChild(pd);


        Element pk = scenarioDocument.createElement("PK");

        Element negligible_concentration = scenarioDocument.createElement("negligible_concentration");
        negligible_concentration.setTextContent("1e-5");

        Element half_life = scenarioDocument.createElement("half_life");
        half_life.setTextContent("0.00069");

        Element vol_dist = scenarioDocument.createElement("vol_dist");
        vol_dist.setTextContent("0.01667");

        pk.appendChild(negligible_concentration);
        pk.appendChild(half_life);
        pk.appendChild(vol_dist);
        drug.appendChild(pk);

        drugDescription.appendChild(drug);

        scenarioElement.insertBefore(drugDescription, modelElement);

        //creating MDADescription element, if there are interventions...

        Element interventions = getChildElement(scenarioElement, "interventions");
        if (interventions.getAttribute("name").equals("A2 Intervention"))
        {
            Element mdaDescription = scenarioDocument.createElement("MDADescription");
            Element mdaSchedule = scenarioDocument.createElement("schedule");

            Element mdaMedicate = scenarioDocument.createElement("medicate");
            Attr mdaDrug = scenarioDocument.createAttribute("drug");
            mdaDrug.setNodeValue("effective");
            Attr mdaHour = scenarioDocument.createAttribute("hour");
            mdaHour.setNodeValue("0");
            Attr mdaMg = scenarioDocument.createAttribute("mg");
            mdaMg.setNodeValue("1");

            mdaMedicate.setAttributeNode(mdaDrug);
            mdaMedicate.setAttributeNode(mdaHour);
            mdaMedicate.setAttributeNode(mdaMg);

            mdaSchedule.appendChild(mdaMedicate);
            mdaDescription.appendChild(mdaSchedule);
            interventions.insertBefore(mdaDescription, interventions.getFirstChild());

        }
        return true;
    }

    private void setMosqsNewAttributes(int mosqType, Element mosq,
                                       NodeList nonHumanHosts) {

        Attr humanBloodIndex = scenarioDocument
                               .createAttribute("mosqHumanBloodIndex");
        Attr proportionLaidEggsSameDay = scenarioDocument
                                         .createAttribute("mosqLaidEggsSameDayProportion");
        Attr PSurvivalFeedingCycle = scenarioDocument
                                     .createAttribute("mosqSurvivalFeedingCycleProbability");

        proportionLaidEggsSameDay
        .setNodeValue(Double
                      .toString(SchemaTranslator.ProporitionsLaidEggsSameDay[mosqType]));
        PSurvivalFeedingCycle.setNodeValue(Double
                                           .toString(SchemaTranslator.PsSurvivalFeedingCycle[mosqType]));

        if (nonHumanHosts == null || nonHumanHosts.getLength() == 0)
            humanBloodIndex.setNodeValue(Double
                                         .toString(SchemaTranslator.HumanBloodIndex_NONNHS));

        else if (nonHumanHosts.getLength() == 1) {
            humanBloodIndex.setNodeValue(Double
                                         .toString(SchemaTranslator.HumanBloodIndexes[mosqType]));

            Element nhh = (Element) nonHumanHosts.item(0);

            Attr relativeEntoAvailability = scenarioDocument
                                            .createAttribute("mosqRelativeEntoAvailability");
            relativeEntoAvailability.setNodeValue(Double
                                                  .toString(SchemaTranslator.Standard_RELATIVE_ENTO_AV));

            nhh.setAttributeNode(relativeEntoAvailability);
            nhh.removeAttribute("mosqEntoAvailability");
        } else {

            humanBloodIndex.setNodeValue(Double
                                         .toString(SchemaTranslator.HumanBloodIndexes[mosqType]));
            System.err
            .println("There are more than 1 non human hosts types in these scenario. Please edit the relative Ento availabilities for each type of non human host by hand.");
        }

        mosq.setAttributeNode(humanBloodIndex);
        mosq.setAttributeNode(proportionLaidEggsSameDay);
        mosq.setAttributeNode(PSurvivalFeedingCycle);

        mosq.removeAttribute("mosqEntoAvailability");
        mosq.removeAttribute("mosqSeekingDeathRate");
    }

    private void visitAllFiles(File file, File outDir) throws Exception {
        if (!outDir.isDirectory())
            outDir.mkdir();
        if (file.isDirectory()) {
            File subDir = new File( outDir, file.getName() );
            String[] children = file.list();
            for (int i = 0; i < children.length; i++) {
                try {
                    visitAllFiles(new File(file, children[i]), subDir);
                } catch (Exception exc) {
                    System.out.println("Error translating " + file + ": " + exc);
                    exc.printStackTrace();
                }
            }
        } else {
            if (file.getName().endsWith(".xml")) {
                System.out.println(file.getAbsolutePath());
                translateFile(file, outDir);
            }
        }
    }

    public static void main(String[] args) {

        String output_folder = "translatedScenarios";
        String input_folder = "scenarios";

        for (int i = 0; i < args.length; i++) {
            // Allow _ instead of - :
            String arg = args[i].replace('_','-');
            if (arg.equals("--required-version")) {
                _required_version = Integer.parseInt(args[++i]);
            } else if (arg.equals("--oneDayTimesteps")) {
                doODTTranslation = true;
                doValidation = false;
                System.out.println("You have chosen the --oneDayTimesteps option, this option is only intended for the fitting scenarii or scenarii using no intervention and/or no ");
            } else if (arg.equals("--latest-schema")) {
                if (latestSchema != SchemaName.versioned) {
                    System.err.println("Warning: overwriting another schema-name option");
                }
                latestSchema = SchemaName.no_suffix;
            } else if (arg.equals("--current-schema")) {
                if (latestSchema != SchemaName.versioned) {
                    System.err.println("Warning: overwriting another schema-name option");
                }
                latestSchema = SchemaName.current;
            } else if (arg.equals("--no-validation")) {
                doValidation = false;
            } else if (arg.equals("--no-translation")) {
                doTranslation = false;
            } else if (arg.equals("--update-db")) {
                doDBUpdate = true;
            } else if (arg.equals("--maxDensCorrection")) {
                String arg2 = args[++i];
                if (arg2.equalsIgnoreCase("true")) {
                    maxDensBug = BugCorrectionBehaviour.correct;
                } else if (arg2.equalsIgnoreCase("false")) {
                    maxDensBug = BugCorrectionBehaviour.dontCorrect;
                } else {
                    System.err
                    .println("--maxDensCorrection: expected true or false");
                    printUsage();
                }
            } else if (arg.equals("--iptiSpOptionWithoutInterventions")) {
                String arg2 = args[++i];
                if (arg2.equalsIgnoreCase("true")) {
                    iptiSpOption = IptiSpBehaviour.assumeIntended;
                } else if (arg2.equalsIgnoreCase("false")) {
                    iptiSpOption = IptiSpBehaviour.assumeUnintented;
                } else {
                    System.err
                    .println("--iptiSpOptionWithoutInterventions: expected true or false");
                    printUsage();
                }
            } else if (arg.equals("--iptiReportOnlyAtRisk")) {
                String arg2 = args[++i];
                if (arg2.equalsIgnoreCase("true")) {
                    iptiROAR = IptiReportOnlyAtRiskBehaviour.on;
                } else if (arg2.equalsIgnoreCase("false")) {
                    iptiROAR = IptiReportOnlyAtRiskBehaviour.off;
                } else {
                    System.err
                    .println("--iptiReportOnlyAtRisk: expected true or false");
                    printUsage();
                }
            } else if (arg.equals("--schema-folder")) {
                schema_folder = args[++i];
            } else if (arg.equals("--input-folder")) {
                input_folder = args[++i];
            } else if (arg.equals("--output-folder")) {
                output_folder = args[++i];
            } else if (arg.equals("--ITN-description")) {
                assert ITN29Translation == ITN29ParameterTranslation.none;      // assert not changed twice
                
                String arg2 = args[++i];
                if (arg2.equalsIgnoreCase("replace")){
                    ITN29Translation = ITN29ParameterTranslation.replace;
                }else if (arg2.equalsIgnoreCase("manual")){
                    ITN29Translation = ITN29ParameterTranslation.manual;
                }else{
                    System.err.println("--ITN-description received unexpected argument: "+arg2);
                    printUsage();
                }
            } else {
                printUsage();
            }
        }
        if (_required_version == 1) {
            System.out
            .println("Target version 1 is not supported (see comment for translate0To1).");
            System.exit(1);
        }

        SchemaTranslator st = new SchemaTranslator();

        try {
            if (doDBUpdate) {
                st.updateDB();
            } else {
                File scenarios = new File(input_folder);
                if (!scenarios.isDirectory())
                    scenarios.mkdir();
                System.out
                .println("Put XMLs to be translated into the \"scenarios\" directory");
                File outDir = new File(output_folder);
                String[] children = scenarios.list();
                for (int i = 0; i < children.length; i++) {
                    st.visitAllFiles(new File(scenarios, children[i]), outDir);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

    }

    private static void printUsage() {
        System.out.println("Usage: schemaTranslator [options]:\n"
                           + "\n  --required-version VERSION\tThe version number to update the document(s) to."
                           + "\n\t\t\t\tDefault: CURRENT_VERSION="+CURRENT_VERSION
                           + "\n  --latest-schema\t\tUse schema scenario.xsd instead of scenario_XX.xsd"
                           + "\n  --current-schema\t\tUse schema scenario_current.xsd instead of scenario_XX.xsd"
                           + "\n  --no-validation\t\tDon't validate the result"
                           + "\n  --no-translation\t\tDon't write out the translated result (but still"
                           + "\n\t\t\t\ttranslate internally for validation)"
                           + "\n  --update-db\t\t\tUpdate DB entries instead of files"
                           + "\n  --maxDensCorrection BOOL\tUpdate 12->13 requires this sometimes: set true to"
                           + "\n\t\t\t\tinclude bug fix, false to explicitly exclude it."
                           + "\n  --iptiSpOptionWithoutInterventions"
                           + "\n\t\t\t\tFor scenarios with iptiDescription but without"
                           + "\n\t\t\t\tinterventions, assume usage of the IPTI model"
                           + "\n\t\t\t\twas (t) intended or (f) a mistake."
                           + "\n  --iptiReportOnlyAtRisk BOOL\tPreviously the IPTI_SP_MODEL option implied this"
                           + "\n\t\t\t\tbehaviour although not necessarily intended; now"
                           + "\n\t\t\t\tthis behaviour is controlled by the separate option"
                           + "\n\t\t\t\tREPORT_ONLY_AT_RISK. Specifying true here causes"
                           + "\n\t\t\t\toption REPORT_ONLY_AT_RISK to be added to scenarios"
                           + "\n\t\t\t\talready using IPTI_SP_MODEL."
                           + "\n  --ITN-description ARG\t\tUpdate 29: new parameterisation is needed."
                           + "\n\t\t\t\tUse ARG \"replace\" to replace old parameters with a new default"
                           + "\n\t\t\t\tparameterisation, or \"manual\" if you will update by hand"
                           + "\n\t\t\t\t(in the second case, you should use --no-validation)."
                           + "\n  --schema-folder\t\tThe schema folder, by default ../../schema"
                           + "\n  --input-folder\t\tThe input folder, by default ./scenarios/"
                           + "\n  --output-folder\t\tThe output folder, by default ./translatedScenarios/"
                          );
        System.exit(1);
    }
}

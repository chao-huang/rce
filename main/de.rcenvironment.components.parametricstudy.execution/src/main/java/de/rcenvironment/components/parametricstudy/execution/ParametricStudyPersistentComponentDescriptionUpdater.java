/*
 * Copyright (C) 2006-2015 DLR, Germany
 * 
 * All rights reserved
 * 
 * http://www.rcenvironment.de/
 */

package de.rcenvironment.components.parametricstudy.execution;

import java.io.IOException;
import java.util.Iterator;
import java.util.UUID;

import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.ObjectWriter;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.JsonNodeFactory;
import org.codehaus.jackson.node.ObjectNode;
import org.codehaus.jackson.node.TextNode;

import de.rcenvironment.components.parametricstudy.common.ParametricStudyComponentConstants;
import de.rcenvironment.core.component.update.api.PersistentComponentDescription;
import de.rcenvironment.core.component.update.api.PersistentComponentDescriptionUpdaterUtils;
import de.rcenvironment.core.component.update.api.PersistentDescriptionFormatVersion;
import de.rcenvironment.core.component.update.spi.PersistentComponentDescriptionUpdater;

/**
 * Implementation of {@link PersistentComponentDescriptionUpdater}.
 * 
 * @author Doreen Seider
 */
public class ParametricStudyPersistentComponentDescriptionUpdater implements PersistentComponentDescriptionUpdater {

    private static final String DESIGN_VARIABLE = "Design Variable";

    private static final String V1_0 = "1.0";

    private static final String V3_0 = "3.0";

    private static final String V3_1 = "3.1";

    private static final String V3_2 = "3.2";

    private static final String OUTPUT_NAME = "name";

    private static final String STATIC_OUTPUTS = "staticOutputs";

    private static final String EP_IDENTIFIER = "epIdentifier";

    @Override
    public String[] getComponentIdentifiersAffectedByUpdate() {
        return ParametricStudyComponentConstants.COMPONENT_IDS;
    }

    @Override
    public int getFormatVersionsAffectedByUpdate(String persistentComponentDescriptionVersion, boolean silent) {

        int versionsToUpdate = PersistentDescriptionFormatVersion.NONE;
        
        if (!silent) {
            if (persistentComponentDescriptionVersion == null
                || persistentComponentDescriptionVersion.compareTo(V1_0) < 0) {
                versionsToUpdate = versionsToUpdate | PersistentDescriptionFormatVersion.BEFORE_VERSON_THREE;
            }
            if (persistentComponentDescriptionVersion != null) {
                if (persistentComponentDescriptionVersion.compareTo(V3_0) < 0) {
                    versionsToUpdate = versionsToUpdate | PersistentDescriptionFormatVersion.FOR_VERSION_THREE;
                }
                if (persistentComponentDescriptionVersion.compareTo(V3_1) < 0) {
                    versionsToUpdate = versionsToUpdate | PersistentDescriptionFormatVersion.AFTER_VERSION_THREE;
                }
            }
        } else if (persistentComponentDescriptionVersion != null
            && persistentComponentDescriptionVersion.compareTo(V3_2) < 0) {
            versionsToUpdate = versionsToUpdate | PersistentDescriptionFormatVersion.AFTER_VERSION_THREE;
        }
        return versionsToUpdate;
    }

    @Override
    public PersistentComponentDescription performComponentDescriptionUpdate(int formatVersion,
        PersistentComponentDescription description, boolean silent)
        throws IOException {
        
        if (!silent) {
            if (formatVersion == PersistentDescriptionFormatVersion.BEFORE_VERSON_THREE) {
                return updateToV10(description);
            }
            if (formatVersion == PersistentDescriptionFormatVersion.FOR_VERSION_THREE) {
                return updateFrom10To30(description);
            }
            if (formatVersion == PersistentDescriptionFormatVersion.AFTER_VERSION_THREE) {
                if (description.getComponentVersion().equals(V3_0)) {
                    description = updateFrom30To31(description);
                    description = updateFrom31To32(description);
                    return description;
                }
            }
        } else {
            if (formatVersion == PersistentDescriptionFormatVersion.AFTER_VERSION_THREE) {
                if (description.getComponentVersion().equals(V3_1)) {
                    return updateFrom31To32(description);
                }
            }
        }
        return description;
    }


    private PersistentComponentDescription updateFrom31To32(PersistentComponentDescription description) throws JsonProcessingException,
        IOException {
        
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(description.getComponentDescriptionAsString());
        JsonNode staticOutputs = node.get(STATIC_OUTPUTS);
        for (JsonNode outputEndpoint : staticOutputs) {
            ((ObjectNode) outputEndpoint).remove(EP_IDENTIFIER);
            if (outputEndpoint.get(OUTPUT_NAME).getTextValue().equals(DESIGN_VARIABLE)) {
                ((ObjectNode) outputEndpoint).put(OUTPUT_NAME, ParametricStudyComponentConstants.OUTPUT_NAME_DV);
            }
        }
        ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
        PersistentComponentDescription newdesc = new PersistentComponentDescription(writer.writeValueAsString(node));
        newdesc.setComponentVersion(V3_2);
        return newdesc;

    }

    private PersistentComponentDescription updateFrom30To31(PersistentComponentDescription description) throws JsonProcessingException,
        IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(description.getComponentDescriptionAsString());
        // remove epIdentifier for static outputs
        JsonNode staticOutputs = node.get(STATIC_OUTPUTS);
        if (staticOutputs != null) {
            for (JsonNode outputEndpoint : staticOutputs) {
                ((ObjectNode) outputEndpoint).remove(EP_IDENTIFIER);
                if (outputEndpoint.get(OUTPUT_NAME).getTextValue().equals("DesignVariable")) {
                    ((ObjectNode) outputEndpoint).put(OUTPUT_NAME, DESIGN_VARIABLE);
                    ObjectNode metadata = createStaticOutputMetaData(node);
                    ((ObjectNode) outputEndpoint).put("metadata", metadata);
                    ((ObjectNode) outputEndpoint).put("datatype", "Float");
                }
            }

        } else {
            ArrayNode statOutputs = JsonNodeFactory.instance.arrayNode();
            ObjectNode output =
                JsonNodeFactory.instance.objectNode();
            output.put("identifier",
                TextNode.valueOf(UUID.randomUUID().toString()));
            output.put(OUTPUT_NAME,
                TextNode.valueOf(DESIGN_VARIABLE));
            output.put("datatype", TextNode.valueOf("Float"));
            ObjectNode metadata = createStaticOutputMetaData(node);
            output.put("metadata", metadata);
            statOutputs.add(output);
            ((ObjectNode) node).put(STATIC_OUTPUTS, statOutputs);
        }

        ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
        PersistentComponentDescription newdesc = new PersistentComponentDescription(writer.writeValueAsString(node));
        newdesc.setComponentVersion(V3_1);
        return newdesc;
    }
    
    private ObjectNode createStaticOutputMetaData(JsonNode node) {
        ObjectNode metadata = JsonNodeFactory.instance.objectNode();
        ObjectNode config = (ObjectNode) node.get("configuration");
        metadata.put("FromValue", TextNode.valueOf(config.get("FromValue").getTextValue()));
        metadata.put("StepSize", TextNode.valueOf(config.get("StepSize").getTextValue()));
        metadata.put("ToValue", TextNode.valueOf(config.get("ToValue").getTextValue()));
        return metadata;
    }

    private PersistentComponentDescription updateFrom10To30(PersistentComponentDescription description) throws JsonProcessingException,
        IOException {

        description =
            PersistentComponentDescriptionUpdaterUtils.updateAllDynamicEndpointsToIdentifier("dynamicInputs", "parameters", description);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(description.getComponentDescriptionAsString());

        // remove epIdentifier for static outputs
        JsonNode staticOutputs = node.get(STATIC_OUTPUTS);
        if (staticOutputs != null) {
            for (JsonNode outputEndpoint : staticOutputs) {
                ((ObjectNode) outputEndpoint).remove(EP_IDENTIFIER);
            }
        }

        ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
        PersistentComponentDescription newdesc = new PersistentComponentDescription(writer.writeValueAsString(node));
        newdesc.setComponentVersion(V3_0);
        return newdesc;
    }

    private PersistentComponentDescription updateToV10(PersistentComponentDescription description) throws JsonParseException, IOException {
        JsonFactory jsonFactory = new JsonFactory();
        JsonParser jsonParser = jsonFactory.createJsonParser(description.getComponentDescriptionAsString());
        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode = mapper.readTree(jsonParser);
        jsonParser.close();
        JsonNode dynamicInputsNode = rootNode.findPath(STATIC_OUTPUTS);
        Iterator<JsonNode> it = dynamicInputsNode.getElements();
        while (it.hasNext()) {
            JsonNode inputNode = it.next();
            ((ObjectNode) inputNode).remove(OUTPUT_NAME);
            ((ObjectNode) inputNode).put(OUTPUT_NAME, TextNode.valueOf(ParametricStudyComponentConstants.OUTPUT_NAME_DV));
        }
        ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
        description = new PersistentComponentDescription(writer.writeValueAsString(rootNode));
        description.setComponentVersion(V1_0);
        return description;
    }
}

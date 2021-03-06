/* SPDX-License-Identifier: Apache 2.0 */
/* Copyright Contributors to the ODPi Egeria project. */
package org.odpi.openmetadata.governanceservers.openlineage.admin;

import org.odpi.openmetadata.adminservices.configuration.properties.OpenLineageServerConfig;
import org.odpi.openmetadata.adminservices.configuration.registration.GovernanceServicesDescription;
import org.odpi.openmetadata.adminservices.ffdc.exception.OMAGConfigurationErrorException;
import org.odpi.openmetadata.commonservices.ffdc.exceptions.PropertyServerException;
import org.odpi.openmetadata.frameworks.connectors.Connector;
import org.odpi.openmetadata.frameworks.connectors.ConnectorBroker;
import org.odpi.openmetadata.frameworks.connectors.ffdc.OCFCheckedExceptionBase;
import org.odpi.openmetadata.frameworks.connectors.properties.beans.Connection;
import org.odpi.openmetadata.governanceservers.openlineage.OpenLineageGraphConnector;
import org.odpi.openmetadata.governanceservers.openlineage.auditlog.OpenLineageServerAuditCode;
import org.odpi.openmetadata.governanceservers.openlineage.buffergraph.BufferGraph;
import org.odpi.openmetadata.governanceservers.openlineage.ffdc.OpenLineageServerErrorCode;
import org.odpi.openmetadata.governanceservers.openlineage.handlers.OpenLineageHandler;
import org.odpi.openmetadata.governanceservers.openlineage.listeners.OpenLineageInTopicListener;
import org.odpi.openmetadata.governanceservers.openlineage.maingraph.MainGraph;
import org.odpi.openmetadata.governanceservers.openlineage.server.OpenLineageServerInstance;
import org.odpi.openmetadata.governanceservers.openlineage.services.StoringServices;
import org.odpi.openmetadata.repositoryservices.auditlog.OMRSAuditLog;
import org.odpi.openmetadata.repositoryservices.connectors.openmetadatatopic.OpenMetadataTopicConnector;
import org.odpi.openmetadata.repositoryservices.connectors.openmetadatatopic.OpenMetadataTopicListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * OpenLineageOperationalServices is responsible for controlling the startup and shutdown of
 * of the open lineage services.
 */
public class OpenLineageServerOperationalServices {
    private static final Logger log = LoggerFactory.getLogger(OpenLineageServerOperationalServices.class);

    private String localServerName;
    private String localServerUserId;
    private String localServerPassword;
    private int maxPageSize;

    private OpenLineageServerConfig openLineageServerConfig;
    private OpenLineageServerInstance openLineageServerInstance = null;
    private OMRSAuditLog auditLog = null;
    private BufferGraph bufferGraphConnector;
    private MainGraph mainGraphConnector;
    private OpenMetadataTopicConnector inTopicConnector;

    /**
     * Constructor used at server startup.
     *
     * @param localServerName     name of the local server
     * @param localServerUserId   user id for this server to use if sending REST requests and
     *                            processing inbound messages.
     * @param localServerPassword password for this server to use if sending REST requests.
     * @param maxPageSize         maximum number of records that can be requested on the pageSize parameter
     */
    public OpenLineageServerOperationalServices(String localServerName,
                                                String localServerUserId,
                                                String localServerPassword,
                                                int maxPageSize) {
        this.localServerName = localServerName;
        this.localServerUserId = localServerUserId;
        this.localServerPassword = localServerPassword;
        this.maxPageSize = maxPageSize;
    }


    /**
     * Initialize the service.
     *
     * @param openLineageServerConfig config properties
     * @param auditLog                destination for audit log messages.
     */
    public void initialize(OpenLineageServerConfig openLineageServerConfig, OMRSAuditLog auditLog) throws OMAGConfigurationErrorException {
        this.openLineageServerConfig = openLineageServerConfig;
        this.auditLog = auditLog;
        final String methodName = "initialize";
        final String actionDescription = "Initialize Open lineage Services";

        logRecord(OpenLineageServerAuditCode.SERVER_INITIALIZING, actionDescription);
        if (openLineageServerConfig == null)
            throwOMAGConfigurationErrorException(OpenLineageServerErrorCode.NO_CONFIG_DOC, methodName, OpenLineageServerAuditCode.NO_CONFIG_DOC, actionDescription);

        try {
            initializeOLS(openLineageServerConfig);
        } catch (OMAGConfigurationErrorException e) {
            throw e;
        } catch (Exception e) {
            exceptionToOMAGConfigurationError(e, OpenLineageServerErrorCode.ERROR_INITIALIZING_OLS, methodName, OpenLineageServerAuditCode.ERROR_INITIALIZING_OLS, actionDescription);
        }
    }

    private void initializeOLS(OpenLineageServerConfig openLineageServerConfig) throws OMAGConfigurationErrorException {
        final String actionDescription = "Initialize Open lineage Services";
        Connection bufferGraphConnection = openLineageServerConfig.getOpenLineageBufferGraphConnection();
        Connection mainGraphConnection = openLineageServerConfig.getOpenLineageMainGraphConnection();
        Connection inTopicConnection = openLineageServerConfig.getInTopicConnection();

        this.bufferGraphConnector = (BufferGraph) getConnector(bufferGraphConnection, OpenLineageServerErrorCode.ERROR_OBTAINING_BUFFER_GRAPH_CONNECTOR, OpenLineageServerAuditCode.ERROR_OBTAINING_BUFFER_GRAPH_CONNNECTOR);
        this.mainGraphConnector = (MainGraph) getConnector(mainGraphConnection, OpenLineageServerErrorCode.ERROR_OBTAINING_MAIN_GRAPH_CONNECTOR, OpenLineageServerAuditCode.ERROR_OBTAINING_MAIN_GRAPH_CONNNECTOR);
        this.inTopicConnector = (OpenMetadataTopicConnector) getConnector(inTopicConnection, OpenLineageServerErrorCode.ERROR_OBTAINING_IN_TOPIC_CONNECTOR, OpenLineageServerAuditCode.ERROR_OBTAINING_IN_TOPIC_CONNECTOR);

        initializeAndStartConnectors();
        OpenLineageHandler openLineageHandler = new OpenLineageHandler(mainGraphConnector);

        this.openLineageServerInstance = new
                OpenLineageServerInstance(
                localServerName,
                GovernanceServicesDescription.OPEN_LINEAGE_SERVICES.getServiceName(),
                maxPageSize,
                openLineageHandler);

        logRecord(OpenLineageServerAuditCode.SERVER_INITIALIZED, actionDescription);
    }

    /**
     * Use the ConnectorBroker to obtain a connector.
     *
     * @param connection the  connection as provided by the user in the configure Open Lineage Services HTTP call.
     * @param errorCode  The error code that should be used when the connector can not be obtained.
     * @param auditCode  The audit code that should be used when the connector can not be obtained.
     * @return The connector returned by the ConnectorBroker
     * @throws OMAGConfigurationErrorException
     */
    private Connector getConnector(Connection connection, OpenLineageServerErrorCode errorCode, OpenLineageServerAuditCode auditCode) throws OMAGConfigurationErrorException {
        final String actionDescription = "Obtaining graph database connector";
        final String methodName = "getGraphConnector";
        Connector connector = null;
        try {
            connector = new ConnectorBroker().getConnector(connection);
        } catch (OCFCheckedExceptionBase e) {
            OCFCheckedExceptionToOMAGConfigurationError(e, auditCode, actionDescription);
        } catch (Exception e) {
            exceptionToOMAGConfigurationError(e, errorCode, methodName, auditCode, actionDescription);
        }
        return connector;
    }

    /**
     * Call the initialize() and start() method for all applicable connectors used by the Open Lineage Services.
     *
     * @throws OMAGConfigurationErrorException
     */
    private void initializeAndStartConnectors() throws OMAGConfigurationErrorException {
        initializeGraphConnectorDB(
                bufferGraphConnector,
                OpenLineageServerErrorCode.ERROR_INITIALIZING_BUFFER_GRAPH_CONNECTOR_DB,
                OpenLineageServerAuditCode.ERROR_INITIALIZING_BUFFER_GRAPH_CONNNECTOR_DB,
                "initializeBufferGraphConnector"
        );

        initializeGraphConnectorDB(
                mainGraphConnector,
                OpenLineageServerErrorCode.ERROR_INITIALIZING_MAIN_GRAPH_CONNECTOR_DB,
                OpenLineageServerAuditCode.ERROR_INITIALIZING_MAIN_GRAPH_CONNECTOR_DB,
                "initializeMainGraphConnector"
        );

        Object mainGraph = mainGraphConnector.getMainGraph();
        bufferGraphConnector.setMainGraph(mainGraph);

        startGraphConnector(bufferGraphConnector,
                OpenLineageServerErrorCode.ERROR_STARTING_BUFFER_GRAPH_CONNECTOR,
                OpenLineageServerAuditCode.ERROR_STARTING_BUFFER_GRAPH_CONNECTOR,
                "startBufferGraphConnector");

        startGraphConnector(mainGraphConnector,
                OpenLineageServerErrorCode.ERROR_OBTAINING_MAIN_GRAPH_CONNECTOR,
                OpenLineageServerAuditCode.ERROR_STARTING_MAIN_GRAPH_CONNECTOR,
                "startMainGraphConnector");

        startIntopicConnector();
    }

    /**
     * Initialize the passed OpenLineageGraphConnector.
     *
     * @param connector         The connector that is to be initialized.
     * @param errorCode         The error code that should be used when the connector can not be initialized.
     * @param auditCode         The audit code that should be used when the connector can not be initialized.
     * @param actionDescription The action taking place in this method, used in error reporting
     * @throws OMAGConfigurationErrorException
     */
    private void initializeGraphConnectorDB(OpenLineageGraphConnector connector, OpenLineageServerErrorCode errorCode, OpenLineageServerAuditCode auditCode, String actionDescription) throws OMAGConfigurationErrorException {
        final String methodName = "initializeGraphConnectorDB";
        try {
            connector.initializeGraphDB();
        } catch (OCFCheckedExceptionBase e) {
            OCFCheckedExceptionToOMAGConfigurationError(e, auditCode, actionDescription);
        } catch (Exception e) {
            exceptionToOMAGConfigurationError(e, errorCode, methodName, auditCode, actionDescription);
        }
    }

    /**
     * Start the passed OpenLineageGraphConnector.
     *
     * @param connector         The connector that is to be started.
     * @param errorCode         The error code that should be used when the connector can not be started.
     * @param auditCode         The audit code that should be used when the connector can not be started.
     * @param actionDescription The action taking place in this method, used in error reporting.
     * @throws OMAGConfigurationErrorException
     */
    private void startGraphConnector(OpenLineageGraphConnector connector, OpenLineageServerErrorCode errorCode, OpenLineageServerAuditCode auditCode, String actionDescription) throws OMAGConfigurationErrorException {
        final String methodName = "startGraphConnector";
        try {
            connector.start();
        } catch (OCFCheckedExceptionBase e) {
            OCFCheckedExceptionToOMAGConfigurationError(e, auditCode, actionDescription);
        } catch (Exception e) {
            exceptionToOMAGConfigurationError(e, errorCode, methodName, auditCode, actionDescription);
        }
    }

    /**
     * Start the Open Lineage Services in-topic connector
     *
     * @throws OMAGConfigurationErrorException
     */
    private void startIntopicConnector() throws OMAGConfigurationErrorException {
        final String actionDescription = "Start the Open Lineage Services in-topic listener";
        final String methodName = "startIntopicConnector";
        final OpenLineageServerAuditCode auditCode = OpenLineageServerAuditCode.ERROR_STARTING_IN_TOPIC_CONNECTOR;
        inTopicConnector.setAuditLog(auditLog);
        StoringServices storingServices = new StoringServices(bufferGraphConnector);
        OpenMetadataTopicListener openLineageInTopicListener = new OpenLineageInTopicListener(storingServices, auditLog);
        inTopicConnector.registerListener(openLineageInTopicListener);
        try {
            inTopicConnector.start();
        } catch (OCFCheckedExceptionBase e) {
            OCFCheckedExceptionToOMAGConfigurationError(e, auditCode, actionDescription);
        } catch (Exception e) {
            exceptionToOMAGConfigurationError(e, OpenLineageServerErrorCode.ERROR_STARTING_IN_TOPIC_CONNECTOR, methodName, auditCode, actionDescription);
        }
        logRecord(OpenLineageServerAuditCode.SERVER_REGISTERED_WITH_IN_TOPIC, actionDescription);
    }


    /**
     * Throw an OMAGConfigurationErrorException using an OpenLineageServerErrorCode.
     *
     * @param errorCode         Details about the exception that occurred, in a format intended for web users.
     * @param methodName        The name of the calling method.
     * @param auditCode         Details about the exception that occurred, in a format intended for system administrators.
     * @param actionDescription The action that was taking place when the error occurred.
     * @throws OMAGConfigurationErrorException
     */
    private void throwOMAGConfigurationErrorException(OpenLineageServerErrorCode errorCode, String methodName, OpenLineageServerAuditCode auditCode, String actionDescription) throws OMAGConfigurationErrorException {
        String errorMessage = errorCode.getErrorMessageId() + errorCode.getFormattedErrorMessage(localServerName);
        OMAGConfigurationErrorException e = new OMAGConfigurationErrorException(errorCode.getHTTPErrorCode(),
                this.getClass().getName(),
                methodName,
                errorMessage,
                errorCode.getSystemAction(),
                errorCode.getUserAction());
        logException(auditCode, actionDescription, e);
        throw e;
    }


    /**
     * Convert an Exception to an OMAGConfigurationErrorException
     *
     * @param e                 The exception object that was thrown
     * @param auditCode         Details about the exception that occurred, in a format intended for system administrators.
     * @param actionDescription The action that was taking place when the exception occurred.
     * @throws OMAGConfigurationErrorException
     */
    private void exceptionToOMAGConfigurationError(Exception e, OpenLineageServerErrorCode errorCode, String methodName, OpenLineageServerAuditCode
            auditCode, String actionDescription) throws OMAGConfigurationErrorException {
        logException(auditCode, actionDescription, e);
        String errorMessage = errorCode.getErrorMessageId() + errorCode.getFormattedErrorMessage(localServerName);
        throw new OMAGConfigurationErrorException(errorCode.getHTTPErrorCode(),
                this.getClass().getName(),
                methodName,
                errorMessage,
                errorCode.getSystemAction(),
                errorCode.getUserAction());
    }

    /**
     * Convert an Exception to a PropertyServerException
     *
     * @param e                 The exception object that was thrown
     * @param auditCode         Details about the exception that occurred, in a format intended for system administrators.
     * @param actionDescription The action that was taking place when the exception occurred.
     * @throws PropertyServerException The service had trouble shutting down.
     */
    private void exceptionToPropertyServerException(Exception e, OpenLineageServerErrorCode errorCode, String methodName, OpenLineageServerAuditCode
            auditCode, String actionDescription) throws PropertyServerException {
        logException(auditCode, actionDescription, e);
        String errorMessage = errorCode.getErrorMessageId() + errorCode.getFormattedErrorMessage(localServerName);
        throw new PropertyServerException(errorCode.getHTTPErrorCode(),
                this.getClass().getName(),
                methodName,
                errorMessage,
                errorCode.getSystemAction(),
                errorCode.getUserAction());
    }

    /**
     * Convert an OCFCheckedExceptionBase exception to an OMAGConfigurationErrorException
     *
     * @param e                 The exception object that was thrown
     * @param auditCode         Details about the exception that occurred, in a format intended for system administrators.
     * @param actionDescription The action that was taking place when the exception occurred.
     * @throws OMAGConfigurationErrorException
     */
    private void OCFCheckedExceptionToOMAGConfigurationError(OCFCheckedExceptionBase e, OpenLineageServerAuditCode auditCode, String actionDescription) throws OMAGConfigurationErrorException {
        logException(auditCode, actionDescription, e);
        throw new OMAGConfigurationErrorException(e.getReportedHTTPCode(),
                e.getReportingClassName(),
                e.getReportingActionDescription(),
                e.getErrorMessage(),
                e.getReportedSystemAction(),
                e.getReportedUserAction());
    }

    /**
     * Shutdown the Open Lineage Services.
     *
     * @return boolean indicated whether the disconnect was successful.
     */
    public boolean shutdown() throws PropertyServerException {
        String actionDescription = "Shutting down the open lineage Services server.";
        logRecord(OpenLineageServerAuditCode.SERVER_SHUTTING_DOWN, actionDescription);

        disconnectInTopicConnector();

        disconnectGraphConnector(bufferGraphConnector,
                OpenLineageServerErrorCode.ERROR_DISCONNECTING_BUFFER_GRAPH_CONNECTOR,
                OpenLineageServerAuditCode.ERROR_DISCONNECTING_BUFFER_GRAPH_CONNECTOR,
                "Disconnecting the Buffergraph connection.");

        disconnectGraphConnector(mainGraphConnector,
                OpenLineageServerErrorCode.ERROR_DISCONNECTING_MAIN_GRAPH_CONNECTOR,
                OpenLineageServerAuditCode.ERROR_DISCONNECTING_MAIN_GRAPH_CONNECTOR,
                "Disconnecting the Maingraph connection.");


        if (openLineageServerInstance != null)
            openLineageServerInstance.shutdown();

        logRecord(OpenLineageServerAuditCode.SERVER_SHUTDOWN, actionDescription);
        return true;
    }


    /**
     * Disconnect the passed OpenLineageGraphConnector.
     *
     * @param connector         The connector that is to be disconnected.
     * @param auditCode         The potential error that could occur, in a format intended for system administrators.
     * @param actionDescription The action taking place in this method, used in error reporting.
     * @throws PropertyServerException
     */
    private void disconnectGraphConnector(OpenLineageGraphConnector connector, OpenLineageServerErrorCode errorCode, OpenLineageServerAuditCode auditCode, String actionDescription) throws PropertyServerException {
        final String methodName = "disconnectGraphConnector";
        if (connector == null)
            return;
        try {
            connector.disconnect();
        } catch (Exception e) {
            exceptionToPropertyServerException(e, errorCode, methodName, auditCode, actionDescription);
        }
    }

    /**
     * Disconnect the Open Lineage Services in-topic connector
     */
    private void disconnectInTopicConnector() throws PropertyServerException {
        final String methodName = "disconnectInTopicConnector";
        final String actionDescription = "Disconnecting the Open Lineage Services in-topic listener";

        if (inTopicConnector == null)
            return;
        try {
            inTopicConnector.disconnect();
        } catch (Exception e) {
            exceptionToPropertyServerException(e, OpenLineageServerErrorCode.ERROR_DISCONNECTING_IN_TOPIC_CONNECTOR, methodName, OpenLineageServerAuditCode.ERROR_DISCONNECTING_IN_TOPIC_CONNECTOR, actionDescription);
        }
    }

    /**
     * Write a non-exception record to the audit log.
     *
     * @param auditCode         Details about the exception that occurred, in a format intended for system administrators.
     * @param actionDescription Describes what the user could do to prevent the error from occurring.
     */
    private void logRecord(OpenLineageServerAuditCode auditCode, String actionDescription) {
        auditLog.logRecord(actionDescription,
                auditCode.getLogMessageId(),
                auditCode.getSeverity(),
                auditCode.getFormattedLogMessage(localServerName),
                null,
                auditCode.getSystemAction(),
                auditCode.getUserAction());
        log.info(auditCode.getSystemAction());
    }

    /**
     * Write an exception to the audit log.
     *
     * @param auditCode         Reference to the specific audit message.
     * @param actionDescription Describes what the user could do to prevent the error from occurring.
     * @param e                 The exception object that was thrown.
     */
    private void logException(OpenLineageServerAuditCode auditCode, String actionDescription, Exception e) {
        auditLog.logException(actionDescription,
                auditCode.getLogMessageId(),
                auditCode.getSeverity(),
                auditCode.getFormattedLogMessage(localServerName, openLineageServerConfig.toString()),
                null,
                auditCode.getSystemAction(),
                auditCode.getUserAction(),
                e);
        log.error(auditCode.getSystemAction(), e);
    }
}


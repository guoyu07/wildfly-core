package org.jboss.as.test.manualmode.auditlog;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.AUDIT_LOG;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HANDLER;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import javax.inject.Inject;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.Operations.CompositeOperationBuilder;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.domain.management.audit.AuditLogLoggerResourceDefinition;
import org.jboss.as.test.integration.management.util.ServerReload;
import org.jboss.dmr.ModelNode;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.core.testrunner.ServerControl;
import org.wildfly.core.testrunner.ServerController;
import org.wildfly.core.testrunner.WildflyTestRunner;
import org.xnio.IoUtils;

/**
 * @author Ondrej Lukas
 *
 *          Test that fields of Audit log from JMX have right content
 */
@RunWith(WildflyTestRunner.class)
@ServerControl(manual = true)
public class JmxAuditLogFieldsOfLogTestCase extends AbstractLogFieldsOfLogTestCase {
    @Inject
    private ServerController container;

    private PathAddress auditLogConfigAddress;

    private JMXConnector connector;
    private MBeanServerConnection connection;

    private static final String JMX = "jmx";
    private static final String CONFIGURATION = "configuration";
    private static final String HANDLER_NAME = "file";

    @Test
    public void testJmxAuditLoggingFields() throws Exception {
        connection = setupAndGetConnection();
        makeOneLog();
        Assert.assertTrue(Files.exists(FILE));
        List<ModelNode> logs = readFile(1);
        ModelNode log = logs.get(0);
        Assert.assertEquals("jmx", log.get("type").asString());
        Assert.assertEquals("true", log.get("r/o").asString());
        Assert.assertEquals("false", log.get("booting").asString());
        Assert.assertTrue(log.get("version").isDefined());
        Assert.assertEquals("IAmAdmin", log.get("user").asString());
        Assert.assertFalse(log.get("domainUUID").isDefined());
        Assert.assertEquals("JMX", log.get("access").asString());
        Assert.assertTrue(log.get("remote-address").isDefined());
        Assert.assertEquals("queryMBeans", log.get("method").asString());
        List<ModelNode> sig = log.get("sig").asList();
        Assert.assertEquals(2, sig.size());
        List<ModelNode> params = log.get("params").asList();
        Assert.assertEquals(2, params.size());
    }

    private void makeOneLog() throws IOException {
        ObjectName objectName;
        try {
            objectName = ObjectName.getInstance("java.lang:*");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        connection.queryNames(objectName, null);
    }

    @Before
    public void beforeTest() throws Exception {
        Files.deleteIfExists(FILE);
        // Start the server
        container.start();
        final ModelControllerClient client = container.getClient().getControllerClient();

        final CompositeOperationBuilder compositeOp = CompositeOperationBuilder.create();

        configureUser(client, compositeOp);

        auditLogConfigAddress = PathAddress.pathAddress(PathElement.pathElement(SUBSYSTEM, JMX),
                PathElement.pathElement(CONFIGURATION, AUDIT_LOG));

        ModelNode op = Util.createAddOperation(auditLogConfigAddress);
        // Don't log boot operations by default
        op.get(AuditLogLoggerResourceDefinition.LOG_BOOT.getName()).set(false);
        op.get(AuditLogLoggerResourceDefinition.LOG_READ_ONLY.getName()).set(true);
        op.get(AuditLogLoggerResourceDefinition.ENABLED.getName()).set(true);
        compositeOp.addStep(op);
        compositeOp.addStep(Util.createAddOperation(PathAddress.pathAddress(auditLogConfigAddress,
                PathElement.pathElement(HANDLER, HANDLER_NAME))));

        executeForSuccess(client, compositeOp.build());
        ServerReload.executeReloadAndWaitForCompletion(client);
    }

    @After
    public void afterTest() throws Exception {
        final ModelControllerClient client = container.getClient().getControllerClient();
        IoUtils.safeClose(connector);

        final CompositeOperationBuilder compositeOp = CompositeOperationBuilder.create();
        resetUser(compositeOp);
        compositeOp.addStep(Util.getResourceRemoveOperation(PathAddress.pathAddress(auditLogConfigAddress,
                PathElement.pathElement(HANDLER, HANDLER_NAME))));
        compositeOp.addStep(Util.getResourceRemoveOperation(auditLogConfigAddress));

        try {
            executeForSuccess(client, compositeOp.build());
        } finally {
            Files.deleteIfExists(FILE);
            container.stop();
        }
    }

    private MBeanServerConnection setupAndGetConnection() throws Exception {
        String urlString = System.getProperty("jmx.service.url",
                "service:jmx:remote+http://" + container.getClient().getMgmtAddress() + ":" + container.getClient().getMgmtPort());
        JMXServiceURL serviceURL = new JMXServiceURL(urlString);
        connector = JMXConnectorFactory.connect(serviceURL, null);
        return connector.getMBeanServerConnection();
    }
}

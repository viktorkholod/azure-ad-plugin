package com.microsoft.jenkins.azuread.integrations.casc;

import com.microsoft.jenkins.azuread.AzureAdAuthorizationMatrixNodeProperty;
import com.microsoft.jenkins.azuread.AzureAdMatrixAuthorizationStrategy;
import com.microsoft.jenkins.azuread.AzureSecurityRealm;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.Node;
import hudson.security.AuthorizationStrategy;
import hudson.security.SecurityRealm;
import io.jenkins.plugins.casc.ConfigurationContext;
import io.jenkins.plugins.casc.Configurator;
import io.jenkins.plugins.casc.ConfiguratorRegistry;
import io.jenkins.plugins.casc.misc.ConfiguredWithCode;
import io.jenkins.plugins.casc.misc.JenkinsConfiguredWithCodeRule;
import io.jenkins.plugins.casc.model.CNode;
import io.jenkins.plugins.casc.model.Mapping;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.matrixauth.PermissionEntry;
import org.jenkinsci.plugins.matrixauth.inheritance.NonInheritingStrategy;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.recipes.LocalData;

import java.util.List;
import java.util.logging.Level;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.hamcrest.CoreMatchers.*;

public class ConfigAsCodeTest {
    private static final String TEST_UPN = "abc@jenkins.com";

    @Rule
    public JenkinsConfiguredWithCodeRule j = new JenkinsConfiguredWithCodeRule();

    @Rule
    public LoggerRule l = new LoggerRule().record(AzureAdMatrixAuthorizationStrategyConfigurator.class, Level.WARNING).capture(20);

    @Test
    @ConfiguredWithCode("configuration-as-code.yml")
    public void should_support_configuration_as_code() {
        SecurityRealm securityRealm = j.jenkins.getSecurityRealm();
        assertTrue("security realm", securityRealm instanceof AzureSecurityRealm);
        AzureSecurityRealm azureSecurityRealm = (AzureSecurityRealm) securityRealm;
        assertNotEquals("clientId", azureSecurityRealm.getClientIdSecret());
        assertNotEquals("clientSecret", azureSecurityRealm.getClientSecretSecret());
        assertNotEquals("tenantId", azureSecurityRealm.getTenantSecret());
        assertEquals("clientId", azureSecurityRealm.getClientId());
        assertEquals("clientSecret", azureSecurityRealm.getClientSecret().getPlainText());
        assertEquals("tenantId", azureSecurityRealm.getTenant());
        assertEquals(0, azureSecurityRealm.getCacheDuration());
        assertTrue(azureSecurityRealm.isFromRequest());

        AuthorizationStrategy authorizationStrategy = j.jenkins.getAuthorizationStrategy();
        assertTrue("authorization strategy", authorizationStrategy instanceof AzureAdMatrixAuthorizationStrategy);
        AzureAdMatrixAuthorizationStrategy azureAdMatrixAuthorizationStrategy = (AzureAdMatrixAuthorizationStrategy) authorizationStrategy;

        assertEquals("one real user sid", 2, azureAdMatrixAuthorizationStrategy.getAllPermissionEntries().size());
        assertTrue("anon can read", azureAdMatrixAuthorizationStrategy.hasExplicitPermission(PermissionEntry.user("anonymous"), Jenkins.READ));
        assertTrue("authenticated can read", azureAdMatrixAuthorizationStrategy.hasExplicitPermission(PermissionEntry.user(TEST_UPN), Jenkins.READ));
        assertTrue("authenticated can build", azureAdMatrixAuthorizationStrategy.hasExplicitPermission(PermissionEntry.group("authenticated"), Item.BUILD));
        assertTrue("authenticated can delete jobs", azureAdMatrixAuthorizationStrategy.hasExplicitPermission(PermissionEntry.user(TEST_UPN), Item.DELETE));
        assertTrue("authenticated can administer", azureAdMatrixAuthorizationStrategy.hasExplicitPermission(PermissionEntry.user(TEST_UPN), Jenkins.ADMINISTER));

        assertEquals("no warnings", 0, l.getMessages().size());

        {
            Node agent = j.jenkins.getNode("agent");
            assertThat(agent, is(notNullValue()));
            assertThat(agent.getDisplayName(), is(equalTo("agent")));
            AzureAdAuthorizationMatrixNodeProperty nodeProperty =
                    agent.getNodeProperty(AzureAdAuthorizationMatrixNodeProperty.class);
            assertThat(nodeProperty, is(notNullValue()));
            assertThat(nodeProperty.getInheritanceStrategy(), instanceOf(NonInheritingStrategy.class));
            assertThat(
                    nodeProperty
                            .hasExplicitPermission(PermissionEntry.user("Adele Vance (be674052-e519-4231-b5e7-2b390bff6346)"),
                                    Computer.BUILD),
                    is(true)
            );
        }
    }

    @Test
    @LocalData
    public void export_configuration() throws Exception {
        ConfiguratorRegistry registry = ConfiguratorRegistry.get();
        ConfigurationContext context = new ConfigurationContext(registry);

        SecurityRealm securityRealm = j.jenkins.getSecurityRealm();
        Configurator<SecurityRealm> realmConfigurator = context.lookupOrFail(AzureSecurityRealm.class);
        CNode realmNode = realmConfigurator.describe(securityRealm, context);
        assertNotNull(realmNode);
        Mapping realMapping = realmNode.asMapping();
        assertEquals(5, realMapping.size());

        AzureSecurityRealm azureSecurityRealm = (AzureSecurityRealm) securityRealm;
        String encryptedClientSecret = azureSecurityRealm.getClientSecretSecret();
        String clientSecret = realMapping.getScalarValue("clientSecret");
        assertEquals(clientSecret, encryptedClientSecret);

        AuthorizationStrategy authorizationStrategy = j.jenkins.getAuthorizationStrategy();
        Configurator<AuthorizationStrategy> c = context.lookupOrFail(AzureAdMatrixAuthorizationStrategy.class);

        CNode node = c.describe(authorizationStrategy, context);
        assertNotNull(node);
        Mapping mapping = node.asMapping();

        List<CNode> permissions = mapping.get("permissions").asSequence();
        assertEquals("list size", 18, permissions.size());
    }
}

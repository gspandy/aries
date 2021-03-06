/**
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.aries.jmx.framework;

import static org.apache.aries.itest.ExtraOptions.mavenBundle;
import static org.apache.aries.itest.ExtraOptions.paxLogging;
import static org.apache.aries.itest.ExtraOptions.testOptions;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.provision;
import static org.ops4j.pax.swissbox.tinybundles.core.TinyBundles.newBundle;
import static org.ops4j.pax.swissbox.tinybundles.core.TinyBundles.withBnd;
import static org.osgi.jmx.framework.BundleStateMBean.OBJECTNAME;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import javax.management.Notification;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

import org.apache.aries.jmx.AbstractIntegrationTest;
import org.apache.aries.jmx.codec.BundleData.Header;
import org.junit.Test;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.container.def.PaxRunnerOptions;
import org.ops4j.pax.exam.junit.Configuration;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.Version;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.jmx.framework.BundleStateMBean;

/**
 * @version $Rev$ $Date$
 */
public class BundleStateMBeanTest extends AbstractIntegrationTest {

    @Configuration
    public static Option[] configuration() {
        return testOptions(
                        // new VMOption( "-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=8000" ),
                        // new TimeoutOption( 0 ),

                        PaxRunnerOptions.rawPaxRunnerOption("config", "classpath:ss-runner.properties"),
                        CoreOptions.equinox().version("3.7.0.v20110613"),
                        paxLogging("INFO"),

                        mavenBundle("org.apache.felix", "org.apache.felix.configadmin"),
                        mavenBundle("org.osgi", "org.osgi.compendium"),
                        mavenBundle("org.apache.aries.jmx", "org.apache.aries.jmx"),
                        mavenBundle("org.apache.aries.jmx", "org.apache.aries.jmx.api"),
                        mavenBundle("org.apache.aries.jmx", "org.apache.aries.jmx.whiteboard"),
                        mavenBundle("org.apache.aries", "org.apache.aries.util"),
                        provision(newBundle()
                                .add(org.apache.aries.jmx.test.bundlea.Activator.class)
                                .add(org.apache.aries.jmx.test.bundlea.api.InterfaceA.class)
                                .add(org.apache.aries.jmx.test.bundlea.impl.A.class)
                                .set(Constants.BUNDLE_SYMBOLICNAME, "org.apache.aries.jmx.test.bundlea")
                                .set(Constants.BUNDLE_VERSION, "2.0.0")
                                .set(Constants.EXPORT_PACKAGE, "org.apache.aries.jmx.test.bundlea.api;version=2.0.0")
                                .set(Constants.IMPORT_PACKAGE,
                                        "org.osgi.framework;version=1.5.0,org.osgi.util.tracker,org.apache.aries.jmx.test.bundleb.api;version=1.1.0;resolution:=optional" +
                                        ",org.osgi.service.cm")
                                .set(Constants.BUNDLE_ACTIVATOR,
                                        org.apache.aries.jmx.test.bundlea.Activator.class.getName())
                                .build(withBnd())),
                        provision(newBundle()
                                .add(org.apache.aries.jmx.test.bundleb.Activator.class)
                                .add(org.apache.aries.jmx.test.bundleb.api.InterfaceB.class)
                                .add(org.apache.aries.jmx.test.bundleb.api.MSF.class)
                                .add(org.apache.aries.jmx.test.bundleb.impl.B.class)
                                .set(Constants.BUNDLE_SYMBOLICNAME,"org.apache.aries.jmx.test.bundleb")
                                .set(Constants.BUNDLE_VERSION, "1.0.0")
                                .set(Constants.EXPORT_PACKAGE,"org.apache.aries.jmx.test.bundleb.api;version=1.1.0")
                                .set(Constants.IMPORT_PACKAGE,"org.osgi.framework;version=1.5.0,org.osgi.util.tracker," +
                                		"org.osgi.service.cm,org.apache.aries.jmx.test.fragmentc")
                                .set(Constants.BUNDLE_ACTIVATOR,
                                        org.apache.aries.jmx.test.bundleb.Activator.class.getName())
                                .build(withBnd())),
                        provision(newBundle()
                                .add(org.apache.aries.jmx.test.fragmentc.C.class)
                                .set(Constants.BUNDLE_SYMBOLICNAME, "org.apache.aries.jmx.test.fragc")
                                .set(Constants.FRAGMENT_HOST, "org.apache.aries.jmx.test.bundlea")
                                .set(Constants.EXPORT_PACKAGE, "org.apache.aries.jmx.test.fragmentc")
                                .build(withBnd())),
                        provision(newBundle()
                                .set(Constants.BUNDLE_SYMBOLICNAME, "org.apache.aries.jmx.test.bundled")
                                .set(Constants.BUNDLE_VERSION, "3.0.0")
                                .set(Constants.REQUIRE_BUNDLE, "org.apache.aries.jmx.test.bundlea;bundle-version=2.0.0")
                                .build(withBnd())),
                        provision(newBundle()
                                .set(Constants.BUNDLE_SYMBOLICNAME, "org.apache.aries.jmx.test.bundlee")
                                .set(Constants.BUNDLE_DESCRIPTION, "%desc")
                                .add("OSGI-INF/l10n/bundle.properties", getBundleProps("desc", "Description"))
                                .add("OSGI-INF/l10n/bundle_nl.properties", getBundleProps("desc", "Omschrijving"))
                                .build(withBnd()))
                        );
    }

    private static InputStream getBundleProps(String key, String value) {
        try {
            Properties p = new Properties();
            p.put(key, value);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            p.store(baos, "");
            return new ByteArrayInputStream(baos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void doSetUp() throws Exception {
        waitForMBean(new ObjectName(BundleStateMBean.OBJECTNAME));
    }

    @Test
    public void testObjectName() throws Exception {
        Set<ObjectName> names = mbeanServer.queryNames(new ObjectName(BundleStateMBean.OBJECTNAME + ",*"), null);
        assertEquals(1, names.size());
        ObjectName name = names.iterator().next();
        Hashtable<String, String> props = name.getKeyPropertyList();
        assertEquals(context().getProperty(Constants.FRAMEWORK_UUID), props.get("uuid"));
        assertEquals(context().getBundle(0).getSymbolicName(), props.get("framework"));
    }

    @Test
    public void testMBeanInterface() throws Exception {
        ObjectName objectName = waitForMBean(new ObjectName(BundleStateMBean.OBJECTNAME));

        BundleStateMBean mbean = getMBean(objectName, BundleStateMBean.class);
        assertNotNull(mbean);

        //get bundles

        Bundle a = context().getBundleByName("org.apache.aries.jmx.test.bundlea");
        assertNotNull(a);

        Bundle b = context().getBundleByName("org.apache.aries.jmx.test.bundleb");
        assertNotNull(b);

        Bundle frag = context().getBundleByName("org.apache.aries.jmx.test.fragc");
        assertNotNull(frag);

        Bundle d = context().getBundleByName("org.apache.aries.jmx.test.bundled");
        assertNotNull(d);

        // exportedPackages

        String[] exports = mbean.getExportedPackages(a.getBundleId());
        assertEquals(2, exports.length);

        List<String> packages = Arrays.asList(exports);
        assertTrue(packages.contains("org.apache.aries.jmx.test.bundlea.api;2.0.0"));
        assertTrue(packages.contains("org.apache.aries.jmx.test.fragmentc;0.0.0"));

        //fragments

        long[] fragments = mbean.getFragments(a.getBundleId());
        assertEquals(1, fragments.length);
        assertEquals(frag.getBundleId() , fragments[0]);

        //headers

        TabularData headers = mbean.getHeaders(b.getBundleId());
        assertNotNull(headers);
        assertEquals(BundleStateMBean.HEADERS_TYPE, headers.getTabularType());
        assertTrue(headers.values().size() >= 4 );
        assertEquals("org.apache.aries.jmx.test.bundleb", Header.from(headers.get(new Object[] {Constants.BUNDLE_SYMBOLICNAME})).getValue());

        //hosts

        long[] hosts = mbean.getHosts(frag.getBundleId());
        assertEquals(1, hosts.length);
        assertEquals(a.getBundleId() , hosts[0]);

        //imported packages

        String[] imports = mbean.getImportedPackages(a.getBundleId());
        assertTrue(imports.length >= 3);
        List<String> importedPackages = Arrays.asList(imports);

        Version version = getPackageVersion("org.osgi.framework");
        assertTrue(importedPackages.contains("org.osgi.framework;" + version.toString()));
        assertTrue(importedPackages.contains("org.apache.aries.jmx.test.bundleb.api;1.1.0"));

        //last modified

        assertTrue(mbean.getLastModified(b.getBundleId()) > 0);

        //location

        assertEquals(b.getLocation(), mbean.getLocation(b.getBundleId()));

        //registered services

        long[] serviceIds = mbean.getRegisteredServices(a.getBundleId());
        assertEquals(1, serviceIds.length);

        //required bundles

        long[] required = mbean.getRequiredBundles(d.getBundleId());
        assertEquals(1, required.length);
        assertEquals(a.getBundleId(), required[0]);

        //requiring bundles

        long[] requiring = mbean.getRequiringBundles(a.getBundleId());
        assertEquals(3, requiring.length);
        assertTrue(b.getSymbolicName(), arrayContains(b.getBundleId(), requiring));
        assertTrue(frag.getSymbolicName(), arrayContains(frag.getBundleId(), requiring));
        assertTrue(d.getSymbolicName(), arrayContains(d.getBundleId(), requiring));

        //services in use

        long[] servicesInUse = mbean.getServicesInUse(a.getBundleId());
        assertEquals(1, servicesInUse.length);

        //start level

        long startLevel = mbean.getStartLevel(b.getBundleId());
        assertTrue(startLevel >= 0);

        //state

        assertEquals("ACTIVE", mbean.getState(b.getBundleId()));

        //isFragment

        assertFalse(mbean.isFragment(b.getBundleId()));
        assertTrue(mbean.isFragment(frag.getBundleId()));

        //isRemovalPending
        assertFalse(mbean.isRemovalPending(b.getBundleId()));

        // isRequired

        assertTrue(mbean.isRequired(a.getBundleId()));
        assertTrue(mbean.isRequired(b.getBundleId()));

        // listBundles

        TabularData bundlesTable = mbean.listBundles();
        assertNotNull(bundlesTable);
        assertEquals(BundleStateMBean.BUNDLES_TYPE, bundlesTable.getTabularType());
        assertEquals(bundleContext.getBundles().length, bundlesTable.values().size());


        // notifications

        final List<Notification> received = new ArrayList<Notification>();

        mbeanServer.addNotificationListener(objectName, new NotificationListener() {
            public void handleNotification(Notification notification, Object handback) {
               received.add(notification);
            }
        }, null, null);

        assertEquals(Bundle.ACTIVE, b.getState());
        b.stop();
        assertEquals(Bundle.RESOLVED, b.getState());
        b.start();
        assertEquals(Bundle.ACTIVE, b.getState());

        int i = 0;
        while (received.size() < 2 && i < 3) {
            Thread.sleep(1000);
            i++;
        }

        assertEquals(2, received.size());

    }

    @Test
    public void testBundleIDsAttribute() throws Exception{
        Set<Long> expectedIDs = new HashSet<Long>();
        for (Bundle b : context().getBundles()) {
            expectedIDs.add(b.getBundleId());
        }

        BundleStateMBean mbean = getMBean(OBJECTNAME, BundleStateMBean.class);
        long[] actual = mbean.getBundleIds();
        Set<Long> actualIDs = new HashSet<Long>();
        for (long id : actual) {
            actualIDs.add(id);
        }

        assertEquals(expectedIDs, actualIDs);
    }

    @Test
    @SuppressWarnings({ "unchecked" })
    public void testHeaderLocalization() throws Exception {
        BundleStateMBean mbean = getMBean(OBJECTNAME, BundleStateMBean.class);
        Bundle b = context().getBundleByName("org.apache.aries.jmx.test.bundlee");

        CompositeData cd = mbean.getBundle(b.getBundleId());
        long id = (Long) cd.get(BundleStateMBean.IDENTIFIER);
        assertEquals("Description", mbean.getHeader(id, Constants.BUNDLE_DESCRIPTION));
        assertEquals("Description", mbean.getHeader(id, Constants.BUNDLE_DESCRIPTION, "en"));
        assertEquals("Omschrijving", mbean.getHeader(id, Constants.BUNDLE_DESCRIPTION, "nl"));

        TabularData td = mbean.getHeaders(id);
        boolean found = false;
        for (CompositeData d : (Collection<CompositeData>) td.values()) {
            if (Constants.BUNDLE_DESCRIPTION.equals(d.get(BundleStateMBean.KEY))) {
                assertEquals("Description", d.get(BundleStateMBean.VALUE));
                found = true;
                break;
            }
        }
        assertTrue(found);

        TabularData tdNL = mbean.getHeaders(id, "nl");
        boolean foundNL = false;
        for (CompositeData d : (Collection<CompositeData>) tdNL.values()) {
            if (Constants.BUNDLE_DESCRIPTION.equals(d.get(BundleStateMBean.KEY))) {
                assertEquals("Omschrijving", d.get(BundleStateMBean.VALUE));
                foundNL = true;
                break;
            }
        }
        assertTrue(foundNL);
    }

    private Version getPackageVersion(String packageName) {
        Bundle systemBundle = context().getBundle(0);
        BundleWiring wiring = systemBundle.adapt(BundleWiring.class);
        List<BundleCapability> packages = wiring.getCapabilities(BundleRevision.PACKAGE_NAMESPACE);
        for (BundleCapability pkg : packages) {
            Map<String, Object> attrs = pkg.getAttributes();
            if (attrs.get(BundleRevision.PACKAGE_NAMESPACE).equals(packageName)) {
                return (Version) attrs.get(Constants.VERSION_ATTRIBUTE);
            }
        }
        throw new IllegalStateException("Package version not found for " + packageName);
    }

    private static boolean arrayContains(long value, long[] values) {
        for (long i : values) {
            if (i == value) {
                return true;
            }
        }
        return false;
    }
}

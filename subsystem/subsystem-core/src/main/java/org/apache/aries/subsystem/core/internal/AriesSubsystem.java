/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aries.subsystem.core.internal;

import static org.apache.aries.application.utils.AppConstants.LOG_ENTRY;
import static org.apache.aries.application.utils.AppConstants.LOG_EXIT;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.aries.subsystem.core.ResourceHelper;
import org.apache.aries.subsystem.core.archive.DeployedContentHeader;
import org.apache.aries.subsystem.core.archive.DeployedContentHeader.DeployedContent;
import org.apache.aries.subsystem.core.archive.DeploymentManifest;
import org.apache.aries.subsystem.core.archive.ExportPackageCapability;
import org.apache.aries.subsystem.core.archive.ExportPackageHeader;
import org.apache.aries.subsystem.core.archive.Header;
import org.apache.aries.subsystem.core.archive.ImportPackageHeader;
import org.apache.aries.subsystem.core.archive.ImportPackageRequirement;
import org.apache.aries.subsystem.core.archive.ProvideCapabilityCapability;
import org.apache.aries.subsystem.core.archive.ProvideCapabilityHeader;
import org.apache.aries.subsystem.core.archive.ProvisionResourceHeader;
import org.apache.aries.subsystem.core.archive.ProvisionResourceHeader.ProvisionedResource;
import org.apache.aries.subsystem.core.archive.RequireBundleHeader;
import org.apache.aries.subsystem.core.archive.RequireBundleRequirement;
import org.apache.aries.subsystem.core.archive.RequireCapabilityHeader;
import org.apache.aries.subsystem.core.archive.RequireCapabilityRequirement;
import org.apache.aries.subsystem.core.archive.SubsystemArchive;
import org.apache.aries.subsystem.core.archive.SubsystemExportServiceCapability;
import org.apache.aries.subsystem.core.archive.SubsystemExportServiceHeader;
import org.apache.aries.subsystem.core.archive.SubsystemImportServiceHeader;
import org.apache.aries.subsystem.core.archive.SubsystemImportServiceRequirement;
import org.apache.aries.subsystem.core.archive.SubsystemManifest;
import org.apache.aries.subsystem.core.archive.SubsystemTypeHeader;
import org.apache.aries.subsystem.core.resource.SubsystemDirectoryResource;
import org.apache.aries.subsystem.core.resource.SubsystemFileResource;
import org.apache.aries.subsystem.core.resource.tmp.SubsystemResource;
import org.apache.aries.util.io.IOUtils;
import org.eclipse.equinox.region.Region;
import org.eclipse.equinox.region.RegionDigraph;
import org.eclipse.equinox.region.RegionFilter;
import org.eclipse.equinox.region.RegionFilterBuilder;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.Version;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.osgi.service.coordinator.Coordination;
import org.osgi.service.coordinator.CoordinationException;
import org.osgi.service.coordinator.Participant;
import org.osgi.service.repository.RepositoryContent;
import org.osgi.service.resolver.ResolutionException;
import org.osgi.service.subsystem.Subsystem;
import org.osgi.service.subsystem.SubsystemConstants;
import org.osgi.service.subsystem.SubsystemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AriesSubsystem implements Subsystem, Resource {
	public static final String ROOT_SYMBOLIC_NAME = "org.osgi.service.subsystem.root";
	public static final Version ROOT_VERSION = Version.parseVersion("1.0.0");
	public static final String ROOT_LOCATION = "subsystem://?"
			+ SubsystemConstants.SUBSYSTEM_SYMBOLICNAME + '='
			+ ROOT_SYMBOLIC_NAME + '&' + SubsystemConstants.SUBSYSTEM_VERSION
			+ '=' + ROOT_VERSION;
	
	private static final Logger LOGGER = LoggerFactory.getLogger(AriesSubsystem.class);
	
	private static final Map<String, AriesSubsystem> locationToSubsystem = Collections.synchronizedMap(new HashMap<String, AriesSubsystem>());
	private static final ResourceReferences resourceReferences = new ResourceReferences();
	private static final Map<Resource, Set<AriesSubsystem>> resourceToSubsystems = Collections.synchronizedMap(new HashMap<Resource, Set<AriesSubsystem>>());
	
	static synchronized Collection<AriesSubsystem> getSubsystems(Resource resource) {
		// If the provided resource is null, all subsystems are desired.
		if (resource == null)
			return locationToSubsystem.values();
		// Otherwise, only subsystems associated with the provided resource are
		// desired.
		Collection<AriesSubsystem> result = resourceToSubsystems.get(resource);
		if (result == null)
			return Collections.emptyList();
		// TODO Does this need to be a copy? Unmodifiable?
		return result;
	}
	
	private static synchronized void addResourceToSubsystem(Resource resource, AriesSubsystem subsystem) {
		Set<AriesSubsystem> subsystems = resourceToSubsystems.get(resource);
		if (subsystems == null) {
			// TODO The new HashSet needs to be guarded by a lock.
			subsystems = new HashSet<AriesSubsystem>();
			resourceToSubsystems.put(resource, subsystems);
		}
		subsystems.add(subsystem);
	}
	
	private static synchronized void removeResourceToSubsystem(Resource resource, AriesSubsystem subsystem) {
		Set<AriesSubsystem> subsystems = resourceToSubsystems.get(resource);
		if (subsystems == null)
			return;
		subsystems.remove(subsystem);
		if (subsystems.isEmpty())
			resourceToSubsystems.remove(resource);
	}
	
	private static void copyContent(InputStream content, File destination) throws IOException {
		copyContent(
				new BufferedInputStream(content),
				new BufferedOutputStream(new FileOutputStream(destination)));
	}
	
	private static void copyContent(InputStream content, OutputStream destination) throws IOException {
		// TODO What's the optimal byte array size? Put this in a constant?
		byte[] bytes = new byte[2048];
		int read;
		try {
			while ((read = content.read(bytes)) != -1)
				destination.write(bytes, 0, read);
		}
		finally {
			try {
				destination.close();
			}
			catch (IOException e) {}
			try {
				content.close();
			}
			catch (IOException e) {}
		}
	}
	
	private static void unzipContent(File content, File destination) throws IOException {
		unzipContent(
				new BufferedInputStream(new FileInputStream(content)),
				destination);
	}
	
	private static void unzipContent(InputStream content, File destination) throws IOException {
		ZipInputStream zis = new ZipInputStream(content);
		try {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				try {
					File file = new File(destination, entry.getName());
					if (entry.isDirectory()) {
						if (!file.exists() && !file.mkdirs())
							throw new SubsystemException("Failed to create resource directory: " + file);
					}
					// TODO Let's just overwrite any existing resources for now.
//					else if (file.exists())
//						throw new SubsystemException("Resource already exists: " + file);
					else {
						OutputStream fos = new FileOutputStream(file);
						try {
							byte[] bytes = new byte[2048];
							int read;
							while ((read = zis.read(bytes)) != -1)
								fos.write(bytes, 0, read);
						}
						finally {
							try {
								fos.close();
							}
							catch (IOException e) {}
						}
					}
				}
				finally {
					try {
						zis.closeEntry();
					}
					catch (IOException e) {}
				}
			}
		}
		finally {
			try {
				zis.close();
			}
			catch (IOException e) {}
		}
	}

	private static void deleteFile(File file) {
		LOGGER.debug(LOG_ENTRY, "deleteFile", file);
		if (file.isDirectory()) {
			deleteFiles(file.listFiles());
		}
		LOGGER.debug("Deleting file {}", file);
		if (!file.delete())
			LOGGER.warn("Unable to delete file {}", file);
		LOGGER.debug(LOG_EXIT, "deleteFile");
	}
	
	private static void deleteFiles(File[] files) {
		for (File file : files) {
			deleteFile(file);
		}
	}
	
	private final SubsystemArchive archive;
	private final Set<Resource> constituents = Collections.synchronizedSet(new HashSet<Resource>());
	private final File directory;
//	private final SubsystemEnvironment environment;
	private final long id;
	private final String location;
	private final Region region;
	private final SubsystemGraph subsystemGraph;
	
	private boolean autostart;
	private Subsystem.State state = State.INSTALLING;
	 
	public AriesSubsystem() throws Exception {
		// Create the root subsystem.
		LOGGER.debug(LOG_ENTRY, "init");
		// TODO The directory field is kept separate from the archive so that it can be referenced
		// by any embedded child subsystems during archive initialization. See the constructors.
		directory = Activator.getInstance().getBundleContext().getDataFile("");
		archive = new SubsystemArchive(directory);
		DeploymentManifest deploymentManifest = archive.getDeploymentManifest();
		long lastId = 0;
		if (deploymentManifest != null) {
			autostart = Boolean.parseBoolean(deploymentManifest.getHeaders().get(DeploymentManifest.ARIESSUBSYSTEM_AUTOSTART).getValue());
			id = Long.parseLong(deploymentManifest.getHeaders().get(DeploymentManifest.ARIESSUBSYSTEM_ID).getValue());
			lastId = Long.parseLong(deploymentManifest.getHeaders().get(DeploymentManifest.ARIESSUBSYSTEM_LASTID).getValue());
			SubsystemIdentifier.setLastId(lastId);
			location = deploymentManifest.getHeaders().get(DeploymentManifest.ARIESSUBSYSTEM_LOCATION).getValue();
		}
		else {
			autostart = true;
			id = 0;
			location = ROOT_LOCATION;
		}
		region = createRegion(null);
		// TODO The creation of the subsystem manifest is in two places. See other constructor.
		SubsystemManifest subsystemManifest = archive.getSubsystemManifest();
		if (subsystemManifest == null) {
			// This is the first time the root subsystem has been initialized in
			// this framework or a framework clean start was requested.
			SubsystemUri uri = new SubsystemUri(ROOT_LOCATION);
			subsystemManifest = new SubsystemManifest.Builder()
					.symbolicName(uri.getSymbolicName())
					.version(uri.getVersion())
					.content(archive.getResources())
					.type(SubsystemTypeHeader.TYPE_APPLICATION
							+ ';'
							+ SubsystemTypeHeader.DIRECTIVE_PROVISION_POLICY
							+ ":="
							+ SubsystemTypeHeader.PROVISION_POLICY_ACCEPT_DEPENDENCIES)
					.build();
			archive.setSubsystemManifest(subsystemManifest);
		}
		else {
			// Need to generate a new subsystem manifest in order to generated a new deployment manifest based
			// on any persisted resources.
			subsystemManifest = new SubsystemManifest.Builder()
					.symbolicName(getSymbolicName())
					.version(getVersion()).content(archive.getResources())
					.build();
		}
//		environment = new SubsystemEnvironment(this);
		// The root subsystem establishes the subsystem graph;
		subsystemGraph = new SubsystemGraph(this);
		archive.setDeploymentManifest(new DeploymentManifest(
				deploymentManifest, 
				subsystemManifest, 
//				environment,
				new SubsystemResolveContext(this, Collections.EMPTY_LIST),
				autostart,
				id,
				lastId,
				location,
				true,
				true));
		// TODO Begin proof of concept.
		// This is a proof of concept for initializing the relationships between the root subsystem and bundles
		// that already existed in its region. Not sure this will be the final resting place. Plus, there are issues
		// since this does not take into account the possibility of already existing bundles going away or new bundles
		// being installed out of band while this initialization is taking place. Need a bundle event hook for that.
		BundleContext context = Activator.getInstance().getBundleContext();
		for (long id : region.getBundleIds()) {
			BundleRevision br = context.getBundle(id).adapt(BundleRevision.class);
			bundleInstalled(br);
		}
		// TODO End proof of concept.
		LOGGER.debug(LOG_EXIT, "init");
	}
	
	public AriesSubsystem(String location, InputStream content, AriesSubsystem parent) throws Exception {
		// Create a non-root subsystem.
		SubsystemUri uri = null;
		if (location.startsWith("subsystem://"))
			uri = new SubsystemUri(location);
		if (content == null) {
			if (uri != null)
				content = uri.getURL().openStream();
			else
				content = new URL(location).openStream();
			
		}
		this.location = location;
		id = SubsystemIdentifier.getNextId();
		String directoryName = "subsystem" + id;
		// TODO Add to constants.
		String fileName = directoryName + ".esa";
		File zipFile = new File(parent.directory, fileName);
		directory = new File(parent.directory, directoryName);
		if (!directory.mkdir())
			throw new IOException("Unable to make directory for " + directory.getCanonicalPath());
		try {
			copyContent(content, zipFile);
			unzipContent(zipFile, directory);
			archive = new SubsystemArchive(directory);
//			environment = new SubsystemEnvironment(this);
			// Make sure the relevant headers are derived, if absent.
			archive.setSubsystemManifest(new SubsystemManifest(
					archive.getSubsystemManifest(),
					uri == null ? null : uri.getSymbolicName(), 
					uri == null ? null : uri.getVersion(), 
					archive.getResources()));
			SubsystemManifestValidator.validate(this, archive.getSubsystemManifest());
			// Unscoped subsystems don't get their own region. They share the region with their scoped parent.
			if (isFeature())
				region = parent.region;
			else
				region = createRegion(getSymbolicName() + ';' + getVersion() + ';' + getType() + ';' + getSubsystemId());
		}
		catch (Throwable t) {
			deleteFile(directory);
			deleteFile(zipFile);
			if (t instanceof SubsystemException)
				throw (SubsystemException)t;
			throw new SubsystemException(t);
		}
		subsystemGraph = parent.subsystemGraph;
	}
	
	public AriesSubsystem(SubsystemResource resource, AriesSubsystem parent) throws Exception {
		subsystemGraph = parent.subsystemGraph;
		this.location = resource.getLocation();
		id = SubsystemIdentifier.getNextId();
		String directoryName = "subsystem" + id;
//		String fileName = directoryName + ".esa";
//		File zipFile = new File(parent.directory, fileName);
		directory = new File(Activator.getInstance().getBundleContext().getDataFile(""), directoryName);
		if (!directory.mkdir())
			throw new IOException("Unable to make directory for " + directory.getCanonicalPath());
		try {
//			copyContent(resource.getContent(), zipFile);
//			unzipContent(zipFile, directory);
			archive = new SubsystemArchive(resource, directory);
			archive.setSubsystemManifest(resource.getSubsystemManifest());
			if (resource.getDeploymentManifest() != null)
				archive.setDeploymentManifest(resource.getDeploymentManifest());
			SubsystemManifestValidator.validate(this, archive.getSubsystemManifest());
			// Unscoped subsystems don't get their own region. They share the region with their scoped parent.
			if (isFeature())
				region = parent.region;
			else
				region = createRegion(getSymbolicName() + ';' + getVersion() + ';' + getType() + ';' + getSubsystemId());
		}
		catch (Throwable t) {
//			deleteFile(directory);
//			deleteFile(zipFile);
			if (t instanceof SubsystemException)
				throw (SubsystemException)t;
			throw new SubsystemException(t);
		}
	}
	
	public AriesSubsystem(SubsystemArchive archive, AriesSubsystem parent) throws Exception {
		subsystemGraph = parent.subsystemGraph;
		this.archive = archive;
		DeploymentManifest manifest = archive.getDeploymentManifest();
		if (manifest == null)
			throw new IllegalStateException("Missing deployment manifest");
		autostart = Boolean.parseBoolean(manifest.getHeaders().get(DeploymentManifest.ARIESSUBSYSTEM_AUTOSTART).getValue());
		id = Long.parseLong(manifest.getHeaders().get(DeploymentManifest.ARIESSUBSYSTEM_ID).getValue());
		location = manifest.getHeaders().get(DeploymentManifest.ARIESSUBSYSTEM_LOCATION).getValue();
		String directoryName = "subsystem" + id;
		directory = new File(parent.directory, directoryName);
//		environment = new SubsystemEnvironment(this);
		// Unscoped subsystems don't get their own region. They share the region with their scoped parent.
		if (isFeature())
			region = parent.region;
		else
			region = createRegion(getSymbolicName() + ';' + getVersion() + ';' + getType() + ';' + getSubsystemId());
	}
	
	public SubsystemArchive getArchive() {
		return archive;
	}
	
	@Override
	public BundleContext getBundleContext() {
		if (EnumSet.of(State.INSTALL_FAILED, State.UNINSTALLED).contains(
				getState()))
			return null;
		AriesSubsystem subsystem = findScopedSubsystemInRegion();
		return region.getBundle(
				RegionContextBundleHelper.SYMBOLICNAME_PREFIX
						+ subsystem.getSubsystemId(),
				RegionContextBundleHelper.VERSION).getBundleContext();
	}
	
	@Override
	public List<Capability> getCapabilities(String namespace) {
		if (IdentityNamespace.IDENTITY_NAMESPACE.equals(namespace)) {
			Capability capability = new OsgiIdentityCapability(this, getSymbolicName(), getVersion(), getType());
			return Collections.singletonList(capability);
		}
		if (namespace == null) {
			Capability capability = new OsgiIdentityCapability(this, getSymbolicName(), getVersion(), getType());
			List<Capability> result = archive.getSubsystemManifest().toCapabilities(this);
			result.add(capability);
			return result;
		}
		List<Capability> result = archive.getSubsystemManifest().toCapabilities(this);
		for (Iterator<Capability> i = result.iterator(); i.hasNext();)
			if (!i.next().getNamespace().equals(namespace))
				i.remove();
		return result;
	}

	@Override
	public Collection<Subsystem> getChildren() {
		return subsystemGraph.getChildren(this);
	}

	@Override
	public synchronized Collection<Resource> getConstituents() {
		return Collections.unmodifiableCollection(new ArrayList<Resource>(constituents));
	}

	@Override
	public String getLocation() {
		return location;
	}

	@Override
	public Collection<Subsystem> getParents() {
		return subsystemGraph.getParents(this);
	}

	@Override
	public List<Requirement> getRequirements(String namespace) {
		if (namespace == null)
			return archive.getSubsystemManifest().toRequirements(this);
		List<Requirement> result = archive.getSubsystemManifest().toRequirements(this);
		for (Iterator<Requirement> i = result.iterator(); i.hasNext();)
			if (!i.next().getNamespace().equals(namespace))
				i.remove();
		return result;
	}

	@Override
	public synchronized Subsystem.State getState() {
		return state;
	}
	
	@Override
	public Map<String, String> getSubsystemHeaders(Locale locale) {
		Map<String, Header<?>> headers = archive.getSubsystemManifest().getHeaders();
		Map<String, String> result = new HashMap<String, String>(headers.size());
		for (Entry<String, Header<?>> entry: headers.entrySet()) {
			Header<?> value = entry.getValue();
			result.put(entry.getKey(), value.getValue());
		}
		return result;
	}

	@Override
	public long getSubsystemId() {
		return id;
	}

	@Override
	public String getSymbolicName() {
		return archive.getSubsystemManifest().getSubsystemSymbolicNameHeader().getSymbolicName();
	}
	
	@Override
	public String getType() {
		return archive.getSubsystemManifest().getSubsystemTypeHeader().getType();
	}

	@Override
	public Version getVersion() {
		return archive.getSubsystemManifest().getSubsystemVersionHeader().getVersion();
	}

	@Override
	public Subsystem install(String location) throws SubsystemException {
		return install(location, null);
	}
	
	@Override
	public Subsystem install(String location, InputStream content) throws SubsystemException {
		Coordination coordination = Activator.getInstance().getCoordinator().create(getSymbolicName() + '-' + getSubsystemId(), 0);
		Subsystem result = null;
		try {
			result = install(location, content, coordination);
		}
		catch (Throwable t) {
			coordination.fail(t);
		}
		finally {
			try {
				coordination.end();
			}
			catch (CoordinationException e) {
				Throwable t = e.getCause();
				if (t instanceof SubsystemException)
					throw (SubsystemException)t;
				throw new SubsystemException(t);
			}
		}
		return result;
	}
	
	public boolean isApplication() {
		return archive.getSubsystemManifest().getSubsystemTypeHeader().isApplication();
	}

	public boolean isComposite() {
		return archive.getSubsystemManifest().getSubsystemTypeHeader().isComposite();
	}

	public boolean isFeature() {
		return archive.getSubsystemManifest().getSubsystemTypeHeader().isFeature();
	}
	
	/* INSTALLING	Wait, Start
	 * INSTALLED	-
	 * RESOLVING	Wait, Start
	 * RESOLVED		-
	 * STARTING		Noop
	 * ACTIVE		Noop
	 * STOPPING		Wait, Start
	 * UPDATING		Wait, Start
	 * UNINSTALLING	Error
	 * UNINSTALLED	Error
	 */
	@Override
	public synchronized void start() throws SubsystemException {
		State state = getState();
		if (state == State.UNINSTALLING || state == State.UNINSTALLED) {
			throw new SubsystemException("Cannot stop from state " + state);
		}
		if (state == State.INSTALLING || state == State.RESOLVING || state == State.STOPPING) {
			waitForStateChange();
			start();
			return;
		}
		// TODO Should we wait on STARTING to see if the outcome is ACTIVE?
		if (state == State.STARTING || state == State.ACTIVE) {
			return;
		}
		resolve();
		setState(State.STARTING);
		autostart = true;
		// TODO Need to hold a lock here to guarantee that another start
		// operation can't occur when the state goes to RESOLVED.
		// Start the subsystem.
		Coordination coordination = Activator.getInstance()
				.getCoordinator()
				.create(getSymbolicName() + '-' + getSubsystemId(), 0);
		try {
			// TODO Need to make sure the resources are ordered by start level.
			for (Resource resource : resourceReferences.getResources(this)) {
				startResource(resource, coordination);
			}
			setState(State.ACTIVE);
		} catch (Throwable t) {
			coordination.fail(t);
			// TODO Need to reinstate complete isolation by disconnecting the
			// region and transition to INSTALLED.
		} finally {
			try {
				coordination.end();
			} catch (CoordinationException e) {
				setState(State.RESOLVED);
				Throwable t = e.getCause();
				if (t instanceof SubsystemException)
					throw (SubsystemException)t;
				throw new SubsystemException(t);
			}
		}
	}
	
	/* INSTALLING	Noop
	 * INSTALLED	Noop
	 * RESOLVING	Noop
	 * RESOLVED		Noop
	 * STARTING		Wait, Stop
	 * ACTIVE		-
	 * STOPPING		Noop
	 * UPDATING		Noop
	 * UNINSTALLING	Error
	 * UNINSTALLED	Error
	 */
	@Override
	public synchronized void stop() throws SubsystemException {
		// The root subsystem may not be stopped.
		checkRoot();
		autostart = false;
		stop0();
	}
	
	/* INSTALLING	Wait, Uninstall
	 * INSTALLED	-
	 * RESOLVING	Wait, Uninstall
	 * RESOLVED		-
	 * STARTING		Wait, Uninstall
	 * ACTIVE		Stop, Uninstall
	 * STOPPING		Wait, Uninstall
	 * UPDATING		Wait, Uninstall
	 * UNINSTALLING	Noop
	 * UNINSTALLED	Noop
	 */
	@Override
	public synchronized void uninstall() throws SubsystemException {
		// The root subsystem may not be uninstalled.
		checkRoot();
		State state = getState();
		if (state == State.UNINSTALLING || state == State.UNINSTALLED || state == State.INSTALL_FAILED) {
			return;
		}
		else if (state == State.INSTALLING || state == State.RESOLVING || state == State.STARTING || state == State.STOPPING) {
			waitForStateChange();
			uninstall();
		}
		else if (getState() == State.ACTIVE) {
			stop();
			uninstall();
		}
		uninstall(true);
	}
	
	synchronized void bundleInstalled(BundleRevision revision) {
		addResourceToSubsystem(revision, this);
		constituents.add(revision);
	}
	
	synchronized void bundleUninstalled(BundleRevision revision) {
		constituents.remove(revision);
		removeResourceToSubsystem(revision, this);
	}
	
	Region getRegion() {
		return region;
	}
	
	void install() {
		Coordination coordination = Activator.getInstance()
				.getCoordinator()
				.create(getSymbolicName() + "-" + getSubsystemId(), 0);
		try {
			install(coordination, null);
		} catch (Exception e) {
			coordination.fail(e);
		} finally {
			coordination.end();
		}
	}
	
	void stop0() {
		if (getState() == State.UNINSTALLING || getState() == State.UNINSTALLED) {
			throw new SubsystemException("Cannot stop from state " + getState());
		}
		else if (getState() == State.STARTING) {
			waitForStateChange();
			stop();
		}
		else if (getState() != State.ACTIVE) {
			return;
		}
		setState(State.STOPPING);
		// Stop child subsystems first.
		for (Subsystem subsystem : subsystemGraph.getChildren(this)) {
			try {
				stopSubsystemResource((AriesSubsystem)subsystem);
			}
			catch (Exception e) {
				LOGGER.error("An error occurred while stopping resource "
						+ subsystem + " of subsystem " + this, e);
			}
		}
		// For non-root subsystems, stop any remaining constituents.
		if (!isRoot()){
			for (Resource resource : resourceReferences.getResources(this)) {
				// Don't stop the region context bundle.
				if (ResourceHelper.getSymbolicNameAttribute(resource).startsWith(RegionContextBundleHelper.SYMBOLICNAME_PREFIX))
					continue;
				try {
					stopResource(resource);
				} catch (Exception e) {
					LOGGER.error("An error occurred while stopping resource "
							+ resource + " of subsystem " + this, e);
					// TODO Should FAILED go out for each failure?
				}
			}
		}
		// TODO Can we automatically assume it actually is resolved?
		setState(State.RESOLVED);
		try {
			DeploymentManifest manifest = new DeploymentManifest(
					archive.getDeploymentManifest(),
					null,
					null,
					autostart,
					id,
					SubsystemIdentifier.getLastId(),
					location,
					false,
					false);
			archive.setDeploymentManifest(manifest);
		}
		catch (Exception e) {
			throw new SubsystemException(e);
		}
	}
	
	protected boolean contains(Resource resource) {
		return constituents.contains(resource);
	}
	
	protected Collection<Bundle> getBundles() {
		ArrayList<Bundle> result = new ArrayList<Bundle>(constituents.size());
		for (Resource resource : constituents) {
			if (resource instanceof BundleRevision)
				result.add(((BundleRevision)resource).getBundle());
		}
		result.trimToSize();
		return result;
	}
	
	protected synchronized void setState(Subsystem.State state) {
		this.state = state;
		Activator.getInstance().getSubsystemServiceRegistrar().update(this);
		notifyAll();
	}
	
	protected synchronized void waitForStateChange() {
		try {
			wait();
		}
		catch (InterruptedException e) {
			throw new SubsystemException(e);
		}
	}
	
	private void addSubsystemServiceImportToSharingPolicy(
			RegionFilterBuilder builder) throws InvalidSyntaxException {
		builder.allow(
				RegionFilter.VISIBLE_SERVICE_NAMESPACE,
				new StringBuilder("(&(")
						.append(org.osgi.framework.Constants.OBJECTCLASS)
						.append('=').append(Subsystem.class.getName())
						.append(")(")
						.append(Constants.SubsystemServicePropertyRegions)
						.append('=').append(region.getName())
						.append("))").toString());
	}
	
	private void addSubsystemServiceImportToSharingPolicy(RegionFilterBuilder builder, Region to)
			throws InvalidSyntaxException, BundleException {
		// TODO This check seems brittle. There is apparently no constant for
		// the root region's name in Digraph.
		if (to.getName().equals("org.eclipse.equinox.region.kernel"))
			addSubsystemServiceImportToSharingPolicy(builder);
		else {
			to = findRootRegion();
			builder = to.getRegionDigraph().createRegionFilterBuilder();
			addSubsystemServiceImportToSharingPolicy(builder);
			RegionFilter regionFilter = builder.build();
			region.connectRegion(to, regionFilter);
		}
	}
	
	private void checkRoot() {
		if (isRoot()) {
			throw new SubsystemException("This operation may not be performed on the root subsystem");
		}
	}
	
	private Region createRegion(String name) throws BundleException {
		Activator activator = Activator.getInstance();
		RegionDigraph digraph = activator.getRegionDigraph();
		if (name == null)
			return digraph.getRegion(activator.getBundleContext().getBundle());
		Region region = digraph.getRegion(name);
		if (region == null)
			return digraph.createRegion(name);
		return region;
	}
	
	private Region findRootRegion() {
		return findRootSubsystem().region;
	}
	
	private AriesSubsystem findRootSubsystem() {
		AriesSubsystem root = this;
		while (!root.isRoot())
			root = ((AriesSubsystem)root.getParents().iterator().next());
		return root;
	}
	
	private AriesSubsystem findScopedSubsystemInRegion() {
		AriesSubsystem result = this;
		while (!result.isScoped())
			result = (AriesSubsystem)result.getParents().iterator().next();
		return result;
	}
	
	private DeploymentManifest getDeploymentManifest() throws IOException, URISyntaxException, ResolutionException {
		if (archive.getDeploymentManifest() == null)
			archive.setDeploymentManifest(new DeploymentManifest(
					archive.getDeploymentManifest(),
					archive.getSubsystemManifest(), 
					new SubsystemResolveContext(this, Collections.EMPTY_LIST),
					autostart,
					id,
					SubsystemIdentifier.getLastId(),
					location,
					true,
					false));
		return archive.getDeploymentManifest();
	}
	
	private synchronized void install(Coordination coordination, AriesSubsystem parent) throws Exception {
		if (!State.INSTALLING.equals(getState()))
			return;
		if (!isFeature())
			RegionContextBundleHelper.installRegionContextBundle(this);
		Activator.getInstance().getSubsystemServiceRegistrar().register(this, parent);
		Set<Resource> contentResources = new TreeSet<Resource>(
				new Comparator<Resource>() {
					@Override
					public int compare(Resource o1, Resource o2) {
						if (o1.equals(o2))
							// Consistent with equals.
							return 0;
						String t1 = ResourceHelper.getTypeAttribute(o1);
						String t2 = ResourceHelper.getTypeAttribute(o2);
						boolean b1 = IdentityNamespace.TYPE_BUNDLE.equals(t1)
								|| IdentityNamespace.TYPE_FRAGMENT.equals(t1);
						boolean b2 = IdentityNamespace.TYPE_BUNDLE.equals(t2)
								|| IdentityNamespace.TYPE_FRAGMENT.equals(t2);
						if (b1 && !b2)
							// o1 is a bundle or fragment but o2 is not.
							return -1;
						if (!b1 && b2)
							// o1 is not a bundle or fragment but o2 is.
							return 1;
						// Either both or neither are bundles or fragments. In this case we don't care about the order.
						return -1;
					}
				});
		List<Resource> transitiveDependencies = new ArrayList<Resource>();
		// Set up the sharing policy before installing the resources so that the
		// environment can filter out capabilities from dependencies being
		// provisioned to regions that are out of scope. This doesn't hurt
		// anything since the resources are disabled from resolving anyway.
		setImportIsolationPolicy();
		DeploymentManifest manifest = getDeploymentManifest();
		DeployedContentHeader contentHeader = manifest.getDeployedContentHeader();
		SubsystemResolveContext environment = new SubsystemResolveContext(this, Collections.EMPTY_LIST);
		if (contentHeader != null) {
			for (DeployedContent content : contentHeader.getDeployedContents()) {
				Collection<Capability> capabilities = environment.findProviders(
						new OsgiIdentityRequirement(content.getName(), content.getDeployedVersion(), content.getNamespace(), false));
				if (capabilities.isEmpty())
					throw new SubsystemException("Subsystem content resource does not exist: " + content.getName() + ";version=" + content.getDeployedVersion());
				Resource resource = capabilities.iterator().next().getResource();
				contentResources.add(resource);
			}
		}
		ProvisionResourceHeader resourceHeader = manifest.getProvisionResourceHeader();
		if (resourceHeader != null) {
			for (ProvisionedResource content : resourceHeader.getProvisionedResources()) {
				Collection<Capability> capabilities = environment.findProviders(
						new OsgiIdentityRequirement(content.getName(), content.getDeployedVersion(), content.getNamespace(), true));
				if (capabilities.isEmpty())
					throw new SubsystemException("Subsystem provisioned resource does not exist: " + content.getName() + ";version=" + content.getDeployedVersion());
				Resource resource = capabilities.iterator().next().getResource();
				transitiveDependencies.add(resource);
			}
		}
		// Discover and install transitive dependencies.
		for (Resource resource : transitiveDependencies) {
			installResource(resource, coordination, true);
		}
		// Install the content resources.
		for (Resource resource : contentResources) {
			installResource(resource, coordination, false);
		}
		setState(State.INSTALLED);
		if (autostart)
			start();
	}
	
	private synchronized Subsystem install(String location, InputStream content, Coordination coordination) throws SubsystemException {
		SubsystemResource ssr = null;
		try {
			TargetRegion region = new TargetRegion(this);
			ssr = SubsystemResource.newInstance(location, content);
			AriesSubsystem subsystem = locationToSubsystem.get(location);
			if (subsystem != null) {
				if (!region.contains(subsystem))
					throw new SubsystemException("Location already exists but existing subsystem is not part of target region: " + location);
				if (!(subsystem.getSymbolicName().equals(ssr.getSubsystemManifest().getSubsystemSymbolicNameHeader().getSymbolicName())
						&& subsystem.getVersion().equals(ssr.getSubsystemManifest().getSubsystemVersionHeader().getVersion())
						&& subsystem.getType().equals(ssr.getSubsystemManifest().getSubsystemTypeHeader().getType())))
					throw new SubsystemException("Location already exists but symbolic name, version, and type are not the same: " + location);
				subsystemInstalling(subsystem);
				subsystemInstalled(subsystem);
				return subsystem;
			}
			subsystem = (AriesSubsystem)region.find(
					ssr.getSubsystemManifest().getSubsystemSymbolicNameHeader().getSymbolicName(), 
					ssr.getSubsystemManifest().getSubsystemVersionHeader().getVersion());
			if (subsystem != null) {
				if (!subsystem.getType().equals(ssr.getSubsystemManifest().getSubsystemTypeHeader().getType()))
					throw new SubsystemException("Subsystem already exists in target region but has a different type: " + location);
				subsystemInstalling(subsystem);
				subsystemInstalled(subsystem);
				return subsystem;
			}
			subsystem = new AriesSubsystem(ssr, this);
			installResource(subsystem, coordination, false);
			return subsystem;
		}
		catch (SubsystemException e) {
			throw e;
		}
		catch (Exception e) {
			throw new SubsystemException(e);
		}
		finally {
//			if (ssr != null)
//				ssr.close();
			IOUtils.close(content);
		}
	}

	private Resource installBundleResource(Resource resource,
			Coordination coordination, boolean transitive)
			throws BundleException, IOException {
		final BundleRevision revision;
		if (resource instanceof BundleRevision) {
			// This means the resource is an already installed bundle.
			revision = (BundleRevision) resource;
			// Transitive runtime resources need no further processing here.
			if (!transitive) {
				// Need to simulate the install process since an install does
				// not actually occur here, and the event hook is not called.
				bundleInstalled(revision);
			}
			return revision;
		}
		InputStream content = ((RepositoryContent) resource).getContent();
		// By default, the resource is provisioned into this subsystem.
		AriesSubsystem provisionTo = this;
		if (transitive) {
			// But transitive dependencies should be provisioned into the
			// first subsystem that accepts dependencies.
			while (provisionTo.archive.getSubsystemManifest()
					.getSubsystemTypeHeader().getProvisionPolicyDirective()
					.isRejectDependencies()) {
				provisionTo = (AriesSubsystem) provisionTo.getParents()
						.iterator().next();
			}
		}
		String location = provisionTo.getSubsystemId() + "@"
				+ provisionTo.getSymbolicName() + "@"
				+ ResourceHelper.getSymbolicNameAttribute(resource);
		ThreadLocalSubsystem.set(provisionTo);
		Bundle bundle = provisionTo.region.installBundle(location, content);
		revision = bundle.adapt(BundleRevision.class);
		// Only need to add a participant when this subsystem is the actual
		// installer of the bundle.
		coordination.addParticipant(new Participant() {
			public void ended(Coordination coordination) throws Exception {
				// noop
			}

			public void failed(Coordination coordination) throws Exception {
				revision.getBundle().uninstall();
			}
		});
		return revision;
	}

	private void installResource(Resource resource, Coordination coordination, boolean transitive) throws Exception {
		final Resource installed;
		String type = ResourceHelper.getTypeAttribute(resource);
		if (SubsystemConstants.SUBSYSTEM_TYPE_APPLICATION.equals(type)
				|| SubsystemConstants.SUBSYSTEM_TYPE_COMPOSITE.equals(type)
				|| SubsystemConstants.SUBSYSTEM_TYPE_FEATURE.equals(type))
			installed = installSubsystemResource(resource, coordination, transitive);
		else if (IdentityNamespace.TYPE_BUNDLE.equals(type) ||
				IdentityNamespace.TYPE_FRAGMENT.equals(type))
			installed = installBundleResource(resource, coordination, transitive);
		else
			throw new SubsystemException("Unsupported resource type: " + type);
		resourceReferences.addReference(this, installed);
		coordination.addParticipant(new Participant() {
			@Override
			public void ended(Coordination coordination) throws Exception {
				// noop
			}

			@Override
			public void failed(Coordination coordination) throws Exception {
				resourceReferences.removeReference(AriesSubsystem.this, installed);
			}
		});
	}

	private Resource installSubsystemResource(Resource resource, Coordination coordination, boolean transitive) throws Exception {
		final AriesSubsystem subsystem;
		if (resource instanceof SubsystemFileResource) {
			SubsystemFileResource sfr = (SubsystemFileResource)resource;
			subsystem = (AriesSubsystem)install(sfr.getLocation(), sfr.getContent(), coordination);
			return subsystem;
		}
		else if (resource instanceof RepositoryContent) {
			String location = getSubsystemId() + "@" + getSymbolicName() + "@" + ResourceHelper.getSymbolicNameAttribute(resource);
			subsystem = (AriesSubsystem)install(location, ((RepositoryContent)resource).getContent(), coordination);
			return subsystem;
		}
		else if (resource instanceof AriesSubsystem) {
			subsystem = (AriesSubsystem)resource;
		}
		else if (resource instanceof SubsystemDirectoryResource) {
			SubsystemDirectoryResource sdr = (SubsystemDirectoryResource)resource;
			subsystem = new AriesSubsystem(sdr.getArchive(), this);
		}
		else if (resource instanceof SubsystemResource) {
			subsystem = new AriesSubsystem((SubsystemResource)resource, this);
		}
		else {
			throw new IllegalArgumentException("Unrecognized subsystem resource: " + resource);
		}
		// Detect a cycle before becoming a participant; otherwise, install failure cleanup goes awry
		// because the parent in the cycle (i.e. the subsystem attempting to install here) is cleaned up 
		// before the child. This results in the child (i.e. this subsystem) being uninstalled as part
		// of that process, but its state has not moved from INSTALLING to INSTALL_FAILED, which results
		// in an eternal wait for a state change.
		subsystemInstalling(subsystem);
		coordination.addParticipant(new Participant() {
			public void ended(Coordination coordination) throws Exception {
				// noop
			}
	
			public void failed(Coordination coordination) throws Exception {
				subsystem.setState(State.INSTALL_FAILED);
				subsystem.uninstall(false);
				subsystemUninstalled(subsystem);
			}
		});
		subsystem.install(coordination, this);
		subsystemInstalled(subsystem);
		return subsystem;
	}

	private boolean isRoot() {
		return ROOT_LOCATION.equals(getLocation());
	}
	
	private boolean isScoped() {
		return isApplication() || isComposite();
	}
	
	private void resolve() {
		if (state != State.INSTALLED)
			return;
		setState(State.RESOLVING);
		try {
			for (Subsystem child : subsystemGraph.getChildren(this))
				((AriesSubsystem)child).resolve();
			// TODO I think this is insufficient. Do we need both
			// pre-install and post-install environments for the Resolver?
			Collection<Bundle> bundles = getBundles();
			if (!Activator.getInstance().getBundleContext().getBundle(0)
					.adapt(FrameworkWiring.class).resolveBundles(bundles)) {
				LOGGER.error(
						"Unable to resolve bundles for subsystem/version/id {}/{}/{}: {}",
						new Object[] { getSymbolicName(), getVersion(),
								getSubsystemId(), bundles });
				// TODO SubsystemException?
				throw new SubsystemException("Framework could not resolve the bundles");
			}
			setExportIsolationPolicy();
			// TODO Could avoid calling setState (and notifyAll) here and
			// avoid the need for a lock.
			setState(State.RESOLVED);
		}
		catch (Throwable t) {
			setState(State.INSTALLED);
			if (t instanceof SubsystemException)
				throw (SubsystemException)t;
			throw new SubsystemException(t);
		}
	}
	
	private void setExportIsolationPolicy() throws InvalidSyntaxException, IOException, BundleException, URISyntaxException, ResolutionException {
		if (isRoot())
			// Nothing to do if this is the root subsystem.
			return;
		if (isFeature())
			// Features share the same isolation as that of their scoped parent.
			return;
		Region from = ((AriesSubsystem)getParents().iterator().next()).region;
		Region to = region;
		RegionFilterBuilder builder = from.getRegionDigraph().createRegionFilterBuilder();
		if (isComposite()) {
			setExportIsolationPolicy(builder, getDeploymentManifest().getExportPackageHeader());
			setExportIsolationPolicy(builder, getDeploymentManifest().getProvideCapabilityHeader());
			setExportIsolationPolicy(builder, getDeploymentManifest().getSubsystemExportServiceHeader());
			// TODO Implement export isolation policy for composites.
		}
		RegionFilter regionFilter = builder.build();
		if (LOGGER.isDebugEnabled())
			LOGGER.debug("Establishing region connection: from=" + from
					+ ", to=" + to + ", filter=" + regionFilter);
		from.connectRegion(to, regionFilter);
	}
	
	private void setExportIsolationPolicy(RegionFilterBuilder builder, ExportPackageHeader header) throws InvalidSyntaxException {
		if (header == null)
			return;
		String policy = RegionFilter.VISIBLE_PACKAGE_NAMESPACE;
		for (ExportPackageCapability capability : header.toCapabilities(this)) {
			StringBuilder filter = new StringBuilder("(&");
			for (Entry<String, Object> attribute : capability.getAttributes().entrySet())
				filter.append('(').append(attribute.getKey()).append('=').append(attribute.getValue()).append(')');
			filter.append(')');
			if (LOGGER.isDebugEnabled())
				LOGGER.debug("Allowing " + policy + " of " + filter);
			builder.allow(policy, filter.toString());
		}
	}
	
	private void setExportIsolationPolicy(RegionFilterBuilder builder, ProvideCapabilityHeader header) throws InvalidSyntaxException {
		if (header == null)
			return;
		for (ProvideCapabilityHeader.Clause clause : header.getClauses()) {
			ProvideCapabilityCapability capability = new ProvideCapabilityCapability(clause, this);
			String policy = capability.getNamespace();
			StringBuilder filter = new StringBuilder("(&");
			for (Entry<String, Object> attribute : capability.getAttributes().entrySet())
				filter.append('(').append(attribute.getKey()).append('=').append(attribute.getValue()).append(')');
			filter.append(')');
			if (LOGGER.isDebugEnabled())
				LOGGER.debug("Allowing " + policy + " of " + filter);
			builder.allow(policy, filter.toString());
		}
	}
	
	private void setExportIsolationPolicy(RegionFilterBuilder builder, SubsystemExportServiceHeader header) throws InvalidSyntaxException {
		if (header == null)
			return;
		String policy = RegionFilter.VISIBLE_SERVICE_NAMESPACE;
		for (SubsystemExportServiceHeader.Clause clause : header.getClauses()) {
			SubsystemExportServiceCapability capability = new SubsystemExportServiceCapability(clause, this);
			String filter = capability.getDirectives().get(SubsystemExportServiceCapability.DIRECTIVE_FILTER);
			if (LOGGER.isDebugEnabled())
				LOGGER.debug("Allowing " + policy + " of " + filter);
			builder.allow(policy, filter.toString());
		}
	}

	private void setImportIsolationPolicy() throws BundleException, IOException, InvalidSyntaxException, URISyntaxException {
		if (isRoot() || isFeature())
			return;
		Region from = region;
		RegionFilterBuilder builder = from.getRegionDigraph().createRegionFilterBuilder();
		Region to = ((AriesSubsystem)getParents().iterator().next()).region;
		addSubsystemServiceImportToSharingPolicy(builder, to);
		if (isApplication() || isComposite()) {
			// Both applications and composites have Import-Package headers that require processing.
			// In the case of applications, the header is generated.
			Header<?> header = archive.getSubsystemManifest().getImportPackageHeader();
			setImportIsolationPolicy(builder, (ImportPackageHeader)header);
			// Both applications and composites have Require-Capability headers that require processing.
			// In the case of applications, the header is generated.
			header = archive.getSubsystemManifest().getRequireCapabilityHeader();
			setImportIsolationPolicy(builder, (RequireCapabilityHeader)header);
			// Both applications and composites have Subsystem-ImportService headers that require processing.
			// In the case of applications, the header is generated.
			header = archive.getSubsystemManifest().getSubsystemImportServiceHeader();
			setImportIsolationPolicy(builder, (SubsystemImportServiceHeader)header);
			header = archive.getSubsystemManifest().getRequireBundleHeader();
			setImportIsolationPolicy(builder, (RequireBundleHeader)header);
		}
		if (isApplication()) {
			// TODO Implement import isolation policy for applications.
			// TODO Support for generic requirements such as osgi.ee.
		}
		else if (isComposite()) {
			// TODO Implement import isolation policy for composites.
			// Composites specify an explicit import policy in their subsystem and deployment manifests.
		}
		RegionFilter regionFilter = builder.build();
		if (LOGGER.isDebugEnabled())
			LOGGER.debug("Establishing region connection: from=" + from
					+ ", to=" + to + ", filter=" + regionFilter);
		from.connectRegion(to, regionFilter);
	}
	
	private void setImportIsolationPolicy(RegionFilterBuilder builder, ImportPackageHeader header) throws InvalidSyntaxException {
		if (header == null)
			return;
		String policy = RegionFilter.VISIBLE_PACKAGE_NAMESPACE;
		for (ImportPackageHeader.Clause clause : header.getClauses()) {
			ImportPackageRequirement requirement = new ImportPackageRequirement(clause, this);
			String filter = requirement.getDirectives().get(ImportPackageRequirement.DIRECTIVE_FILTER);
			if (LOGGER.isDebugEnabled())
				LOGGER.debug("Allowing " + policy + " of " + filter);
			builder.allow(policy, filter);
		}
	}
	
	private void setImportIsolationPolicy(RegionFilterBuilder builder, RequireBundleHeader header) throws InvalidSyntaxException {
		if (header == null)
			return;
		for (RequireBundleHeader.Clause clause : header.getClauses()) {
			RequireBundleRequirement requirement = new RequireBundleRequirement(clause, this);
			String policy = RegionFilter.VISIBLE_REQUIRE_NAMESPACE;
			String filter = requirement.getDirectives().get(RequireBundleRequirement.DIRECTIVE_FILTER);
			if (LOGGER.isDebugEnabled())
				LOGGER.debug("Allowing " + policy + " of " + filter);
			builder.allow(policy, filter);
		}
	}
	
	private void setImportIsolationPolicy(RegionFilterBuilder builder, RequireCapabilityHeader header) throws InvalidSyntaxException {
		if (header == null)
			return;
		for (RequireCapabilityHeader.Clause clause : header.getClauses()) {
			RequireCapabilityRequirement requirement = new RequireCapabilityRequirement(clause, this);
			String policy = requirement.getNamespace();
			String filter = requirement.getDirectives().get(RequireCapabilityRequirement.DIRECTIVE_FILTER);
			if (LOGGER.isDebugEnabled())
				LOGGER.debug("Allowing " + policy + " of " + filter);
			builder.allow(policy, filter);
		}
	}
	
	private void setImportIsolationPolicy(RegionFilterBuilder builder, SubsystemImportServiceHeader header) throws InvalidSyntaxException {
		if (header == null)
			return;
		for (SubsystemImportServiceHeader.Clause clause : header.getClauses()) {
			SubsystemImportServiceRequirement requirement = new SubsystemImportServiceRequirement(clause, this);
			String policy = RegionFilter.VISIBLE_SERVICE_NAMESPACE;
			String filter = requirement.getDirectives().get(SubsystemImportServiceRequirement.DIRECTIVE_FILTER);
			if (LOGGER.isDebugEnabled())
				LOGGER.debug("Allowing " + policy + " of " + filter);
			builder.allow(policy, filter);
		}
	}

	private void startBundleResource(Resource resource, Coordination coordination) throws BundleException {
		final Bundle bundle = ((BundleRevision)resource).getBundle();
		if ((bundle.getState() & (Bundle.STARTING | Bundle.ACTIVE)) != 0)
			return;
		bundle.start(Bundle.START_TRANSIENT | Bundle.START_ACTIVATION_POLICY);
		if (coordination == null)
			return;
		coordination.addParticipant(new Participant() {
			public void ended(Coordination coordination) throws Exception {
				// noop
			}
	
			public void failed(Coordination coordination) throws Exception {
				bundle.stop();
			}
		});
	}

	private void startResource(Resource resource, Coordination coordination) throws BundleException, IOException {
		String type = ResourceHelper.getTypeAttribute(resource);
		if (SubsystemConstants.SUBSYSTEM_TYPE_APPLICATION.equals(type)
				|| SubsystemConstants.SUBSYSTEM_TYPE_COMPOSITE.equals(type)
				|| SubsystemConstants.SUBSYSTEM_TYPE_FEATURE.equals(type))
			startSubsystemResource(resource, coordination);
		else if (IdentityNamespace.TYPE_BUNDLE.equals(type))
			startBundleResource(resource, coordination);
		else if (IdentityNamespace.TYPE_FRAGMENT.equals(type)) {
			// Fragments are not started.
		}
		else
			throw new SubsystemException("Unsupported resource type: " + type);
	}

	private void startSubsystemResource(Resource resource, Coordination coordination) throws IOException {
		final AriesSubsystem subsystem = (AriesSubsystem)resource;
		subsystem.start();
		if (coordination == null)
			return;
		coordination.addParticipant(new Participant() {
			public void ended(Coordination coordination) throws Exception {
				// noop
			}
	
			public void failed(Coordination coordination) throws Exception {
				subsystem.stop();
			}
		});
	}

	private void stopBundleResource(Resource resource) throws BundleException {
		((BundleRevision)resource).getBundle().stop();
	}

	private void stopResource(Resource resource) throws BundleException, IOException {
		String type = ResourceHelper.getTypeAttribute(resource);
		if (SubsystemConstants.SUBSYSTEM_TYPE_APPLICATION.equals(type)
				|| SubsystemConstants.SUBSYSTEM_TYPE_COMPOSITE.equals(type)
				|| SubsystemConstants.SUBSYSTEM_TYPE_FEATURE.equals(type))
			stopSubsystemResource(resource);
		else if (IdentityNamespace.TYPE_BUNDLE.equals(type))
			stopBundleResource(resource);
		else if (IdentityNamespace.TYPE_FRAGMENT.equals(type))
			return;
		else
			throw new SubsystemException("Unsupported resource type: " + type);
	}

	private void stopSubsystemResource(Resource resource) throws IOException {
		((AriesSubsystem)resource).stop();
	}
	
	private synchronized void subsystemInstalled(AriesSubsystem subsystem) {
		Activator.getInstance().getSubsystemServiceRegistrar().addRegion(subsystem, region);
	}
	
	private synchronized void subsystemInstalling(AriesSubsystem subsystem) {
		locationToSubsystem.put(subsystem.getLocation(), subsystem);
		subsystemGraph.add(this, subsystem);
		addResourceToSubsystem(subsystem, this);
		constituents.add(subsystem);
	}
	
	private synchronized void subsystemUninstalled(AriesSubsystem subsystem) {
		Activator.getInstance().getSubsystemServiceRegistrar().removeRegion(subsystem, region);
		constituents.remove(subsystem);
		removeResourceToSubsystem(subsystem, this);
		subsystemGraph.remove(subsystem);
		locationToSubsystem.remove(subsystem.getLocation());
	}
	
	private void uninstall(boolean changeState) {
		if (changeState)
			setState(State.INSTALLED);
		setState(State.UNINSTALLING);
		// Uninstall child subsystems first.
		for (Subsystem subsystem : getChildren()) {
			try {
				uninstallSubsystemResource((AriesSubsystem)subsystem);
			}
			catch (Exception e) {
				LOGGER.error("An error occurred while uninstalling resource " + subsystem + " of subsystem " + this, e);
				// TODO Should FAILED go out for each failure?
			}
		}
		// Uninstall any remaining constituents.
		for (Resource resource : resourceReferences.getResources(this)) {
			// Don't uninstall a resource that is still referenced by other subsystems.
			if (resourceReferences.getSubsystems(resource).size() > 1)
				continue;
			// Don't uninstall the region context bundle here.
			if (ResourceHelper.getSymbolicNameAttribute(resource).startsWith(RegionContextBundleHelper.SYMBOLICNAME_PREFIX))
				continue;
			try {
				uninstallResource(resource);
			}
			catch (Exception e) {
				LOGGER.error("An error occurred while uninstalling resource " + resource + " of subsystem " + this, e);
				// TODO Should FAILED go out for each failure?
			}
		}
		for (Subsystem parent : getParents()) {
			((AriesSubsystem)parent).constituents.remove(this);
		}
		subsystemGraph.remove(this);
		locationToSubsystem.remove(location);
		deleteFile(directory);
		setState(State.UNINSTALLED);
		Activator.getInstance().getSubsystemServiceRegistrar().unregister(this);
		if (!isFeature())
			RegionContextBundleHelper.uninstallRegionContextBundle(this);
	}

	private void uninstallBundleResource(Resource resource) throws BundleException {
		LOGGER.debug(LOG_ENTRY, "uninstallBundleResource", resource);
		BundleRevision revision = (BundleRevision)resource;
		if (getSubsystems(revision).size() > 1) {
			bundleUninstalled(revision);
			return;
		}
		Bundle bundle = revision.getBundle();
		LOGGER.debug("Uninstalling bundle {}", bundle);
		bundle.uninstall();
		LOGGER.debug(LOG_EXIT, "uninstallBundleResource");
	}

	private void uninstallResource(Resource resource) throws BundleException {
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug(LOG_ENTRY, "uninstallResource", resource);
			LOGGER.debug("Subsystem {} is uninstalling resource {};{};{}", new Object[]{
					getSymbolicName(),
					ResourceHelper.getSymbolicNameAttribute(resource),
					ResourceHelper.getVersionAttribute(resource),
					ResourceHelper.getTypeAttribute(resource)
			});
		}
		resourceReferences.removeReference(this, resource);
		String type = ResourceHelper.getTypeAttribute(resource);
		if (SubsystemConstants.SUBSYSTEM_TYPE_APPLICATION.equals(type)
				|| SubsystemConstants.SUBSYSTEM_TYPE_COMPOSITE.equals(type)
				|| SubsystemConstants.SUBSYSTEM_TYPE_FEATURE.equals(type))
			uninstallSubsystemResource(resource);
		else if (IdentityNamespace.TYPE_BUNDLE.equals(type) || IdentityNamespace.TYPE_FRAGMENT.equals(type))
			uninstallBundleResource(resource);
		else
			throw new SubsystemException("Unsupported resource type: " + type);
		LOGGER.debug(LOG_EXIT, "uninstallResource");
	}

	private void uninstallSubsystemResource(Resource resource) {
		removeResourceToSubsystem(resource, this);
		((AriesSubsystem)resource).uninstall();
	}
}

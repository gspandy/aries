package org.apache.aries.subsystem.core.archive;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.apache.aries.subsystem.core.internal.Activator;
import org.apache.aries.subsystem.core.internal.OsgiIdentityRequirement;
import org.apache.aries.subsystem.core.internal.SubsystemResolveContext;
import org.apache.aries.util.manifest.ManifestProcessor;
import org.osgi.framework.Constants;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.osgi.service.resolver.ResolutionException;
import org.osgi.service.subsystem.SubsystemConstants;
import org.osgi.service.subsystem.SubsystemException;

public class DeploymentManifest {
	public static class Builder {
		private Map<String, Header<?>> headers = new HashMap<String, Header<?>>();
		
		public DeploymentManifest build() {
			return new DeploymentManifest(headers);
		}
		
		public Builder header(Header<?> value) {
			if (value != null)
				headers.put(value.getName(), value);
			return this;
		}
		
		public Builder manifest(SubsystemManifest value) {
			for (Entry<String, Header<?>> entry : value.getHeaders().entrySet())
				header(entry.getValue());
			return this;
		}
	}
	
	public static final String DEPLOYED_CONTENT = SubsystemConstants.DEPLOYED_CONTENT;
	public static final String DEPLOYMENT_MANIFESTVERSION = SubsystemConstants.DEPLOYMENT_MANIFESTVERSION;
	public static final String EXPORT_PACKAGE = Constants.EXPORT_PACKAGE;
	public static final String IMPORT_PACKAGE = Constants.IMPORT_PACKAGE;
	public static final String PROVIDE_CAPABILITY = Constants.PROVIDE_CAPABILITY;
	public static final String PROVISION_RESOURCE = SubsystemConstants.PROVISION_RESOURCE;
	public static final String REQUIRE_BUNDLE = Constants.REQUIRE_BUNDLE;
	public static final String REQUIRE_CAPABILITY = Constants.REQUIRE_CAPABILITY;
	public static final String SUBSYSTEM_EXPORTSERVICE = SubsystemConstants.SUBSYSTEM_EXPORTSERVICE;
	public static final String SUBSYSTEM_IMPORTSERVICE = SubsystemConstants.SUBSYSTEM_IMPORTSERVICE;
	public static final String SUBSYSTEM_SYMBOLICNAME = SubsystemConstants.SUBSYSTEM_SYMBOLICNAME;
	public static final String SUBSYSTEM_VERSION = SubsystemConstants.SUBSYSTEM_VERSION;
	
	public static final String ARIESSUBSYSTEM_AUTOSTART = "AriesSubsystem-Autostart";
	public static final String ARIESSUBSYSTEM_ID = "AriesSubsystem-Id";
	public static final String ARIESSUBSYSTEM_LASTID = "AriesSubsystem-LastId";
	public static final String ARIESSUBSYSTEM_LOCATION = "AriesSubsystem-Location";
	
	private final Map<String, Header<?>> headers;
	
	public DeploymentManifest(java.util.jar.Manifest manifest) {
		headers = new HashMap<String, Header<?>>();
		for (Entry<Object, Object> entry : manifest.getMainAttributes().entrySet()) {
			String key = String.valueOf(entry.getKey());
			if (key.equals(SubsystemManifest.SUBSYSTEM_SYMBOLICNAME))
				continue;
			headers.put(key, HeaderFactory.createHeader(key, String.valueOf(entry.getValue())));
		}
	}
	
	public DeploymentManifest(File file) throws FileNotFoundException, IOException {
		Manifest manifest = ManifestProcessor.parseManifest(new FileInputStream(file));
		Attributes attributes = manifest.getMainAttributes();
		Map<String, Header<?>> headers = new HashMap<String, Header<?>>(attributes.size() + 4); // Plus the # of potentially derived headers.
		for (Entry<Object, Object> entry : attributes.entrySet()) {
			String key = String.valueOf(entry.getKey());
			headers.put(key, HeaderFactory.createHeader(key, String.valueOf(entry.getValue())));
		}
		this.headers = Collections.unmodifiableMap(headers);
	}
	
	public DeploymentManifest(
			DeploymentManifest deploymentManifest, 
			SubsystemManifest subsystemManifest, 
			SubsystemResolveContext resolveContext,
			boolean autostart, 
			long id, 
			long lastId, 
			String location,
			boolean overwrite,
			boolean acceptDependencies) throws ResolutionException, IOException, URISyntaxException {
		Map<String, Header<?>> headers;
		if (deploymentManifest == null // We're generating a new deployment manifest.
				|| (deploymentManifest != null && overwrite)) { // A deployment manifest already exists but overwriting it with subsystem manifest content is desired.
			headers = computeHeaders(subsystemManifest);
			Collection<Resource> resources = new HashSet<Resource>();
			SubsystemContentHeader contentHeader = subsystemManifest.getSubsystemContentHeader();
			Map<Resource, List<Wire>> resolution = null;
			Collection<Resource> deployedContent = new HashSet<Resource>();
			if (contentHeader != null) {
				for (SubsystemContentHeader.Content content : contentHeader.getContents()) {
					OsgiIdentityRequirement requirement = new OsgiIdentityRequirement(content.getName(), content.getVersionRange(), content.getType(), false);
					Resource resource = resolveContext.findResource(requirement);
					// If the resource is null, can't continue.
					if (resource == null) {
						if (content.isMandatory())
							throw new SubsystemException("Resource does not exist: " + requirement);
						continue;
					}
					resources.add(resource);
				}
				// TODO This does not validate that all content bundles were found.
				resolution = Activator.getInstance().getResolver().resolve(new SubsystemResolveContext(resolveContext.getSubsystem(), resources));
				Collection<Resource> provisionResource = new HashSet<Resource>();
				for (Resource resource : resolution.keySet()) {
					if (contentHeader.contains(resource))
						deployedContent.add(resource);
					else
						provisionResource.add(resource);
				}
				// Make sure any already resolved content resources are added back in.
				deployedContent.addAll(resources);
				headers.put(DEPLOYED_CONTENT, DeployedContentHeader.newInstance(deployedContent));
				if (!provisionResource.isEmpty())
					headers.put(PROVISION_RESOURCE, ProvisionResourceHeader.newInstance(provisionResource));
			}
		}
		else {
			headers = new HashMap<String, Header<?>>(deploymentManifest.getHeaders());
		}
		// TODO DEPLOYMENT_MANIFESTVERSION
		headers.put(ARIESSUBSYSTEM_AUTOSTART, new GenericHeader(ARIESSUBSYSTEM_AUTOSTART, Boolean.toString(autostart)));
		headers.put(ARIESSUBSYSTEM_ID, new GenericHeader(ARIESSUBSYSTEM_ID, Long.toString(id)));
		headers.put(ARIESSUBSYSTEM_LOCATION, new GenericHeader(ARIESSUBSYSTEM_LOCATION, location));
		headers.put(ARIESSUBSYSTEM_LASTID, new GenericHeader(ARIESSUBSYSTEM_LASTID, Long.toString(lastId)));
		this.headers = Collections.unmodifiableMap(headers);
	}
	
	private DeploymentManifest(Map<String, Header<?>> headers) {
		Map<String, Header<?>> map = new HashMap<String, Header<?>>(headers);
		this.headers = Collections.unmodifiableMap(map);
	}
	
	public DeployedContentHeader getDeployedContentHeader() {
		return (DeployedContentHeader)getHeaders().get(DEPLOYED_CONTENT);
	}
	
	public ExportPackageHeader getExportPackageHeader() {
		return (ExportPackageHeader)getHeaders().get(EXPORT_PACKAGE);
	}
	
	public Map<String, Header<?>> getHeaders() {
		return headers;
	}
	
	public ImportPackageHeader getImportPackageHeader() {
		return (ImportPackageHeader)getHeaders().get(IMPORT_PACKAGE);
	}
	
	public ProvideCapabilityHeader getProvideCapabilityHeader() {
		return (ProvideCapabilityHeader)getHeaders().get(PROVIDE_CAPABILITY);
	}
	
	public ProvisionResourceHeader getProvisionResourceHeader() {
		return (ProvisionResourceHeader)getHeaders().get(PROVISION_RESOURCE);
	}
	
	public RequireBundleHeader getRequireBundleHeader() {
		return (RequireBundleHeader)getHeaders().get(REQUIRE_BUNDLE);
	}
	
	public RequireCapabilityHeader getRequireCapabilityHeader() {
		return (RequireCapabilityHeader)getHeaders().get(REQUIRE_CAPABILITY);
	}
	
	public SubsystemExportServiceHeader getSubsystemExportServiceHeader() {
		return (SubsystemExportServiceHeader)getHeaders().get(SUBSYSTEM_EXPORTSERVICE);
	}
	
	public SubsystemImportServiceHeader getSubsystemImportServiceHeader() {
		return (SubsystemImportServiceHeader)getHeaders().get(SUBSYSTEM_IMPORTSERVICE);
	}
	
	public void write(OutputStream out) throws IOException {
		Manifest manifest = new Manifest();
		Attributes attributes = manifest.getMainAttributes();
		// The manifest won't write anything unless the following header is present.
		attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
		for (Entry<String, Header<?>> entry : headers.entrySet()) {
			attributes.putValue(entry.getKey(), entry.getValue().getValue());
		}
		manifest.write(out);
	}
	
	private Map<String, Header<?>> computeHeaders(SubsystemManifest manifest) {
		return new HashMap<String, Header<?>>(manifest.getHeaders());
	}
}

package org.apache.aries.subsystem.core.resource.tmp;

import java.io.IOException;
import java.net.URISyntaxException;

import org.apache.aries.subsystem.core.archive.SubsystemManifest;
import org.apache.aries.util.filesystem.IDirectory;
import org.osgi.service.resolver.ResolutionException;

public class ApplicationResource extends SubsystemResource {
	ApplicationResource(Location location, IDirectory directory, SubsystemManifest manifest) throws IOException, URISyntaxException, ResolutionException {
		super(location, directory, manifest);
	}
}

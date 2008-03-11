package org.eclipse.equinox.internal.p2.extensionlocation;

import java.net.URL;
import org.eclipse.core.runtime.*;
import org.eclipse.equinox.internal.provisional.p2.artifact.repository.IArtifactRepository;
import org.eclipse.equinox.internal.provisional.p2.core.ProvisionException;
import org.eclipse.equinox.internal.provisional.spi.p2.artifact.repository.IArtifactRepositoryFactory;

public class ExtensionLocationArtifactRepositoryFactory implements IArtifactRepositoryFactory {

	public IArtifactRepository create(URL location, String name, String type) throws ProvisionException {
		return null;
	}

	public IArtifactRepository load(URL location, IProgressMonitor monitor) throws ProvisionException {
		return new ExtensionLocationArtifactRepository(location, monitor);
	}

	public IStatus validate(URL location, IProgressMonitor monitor) {
		try {
			ExtensionLocationArtifactRepository.validate(location, monitor);
		} catch (ProvisionException e) {
			return e.getStatus();
		}
		return Status.OK_STATUS;
	}

}

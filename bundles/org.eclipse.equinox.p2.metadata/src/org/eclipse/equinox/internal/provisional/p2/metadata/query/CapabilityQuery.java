/*******************************************************************************
 * Copyright (c) 2007, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.equinox.internal.provisional.p2.metadata.query;

import org.eclipse.equinox.internal.provisional.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.internal.provisional.p2.metadata.IRequiredCapability;
import org.eclipse.equinox.internal.provisional.p2.query.MatchQuery;

/**
 * A query that searches for {@link IInstallableUnit} instances that provide
 * capabilities that match one or more required capabilities.
 */
public class CapabilityQuery extends MatchQuery {
	private IRequiredCapability[] required;

	/**
	 * Creates a new query on the given required capability.
	 * @param required The required capability
	 */
	public CapabilityQuery(IRequiredCapability required) {
		this.required = new IRequiredCapability[] {required};
	}

	/**
	 * Creates a new query on the given required capabilities. The installable
	 * unit must provide capabilities that match all of the given required capabilities
	 * for this query to be satisfied.
	 * @param required The required capabilities
	 */
	public CapabilityQuery(IRequiredCapability[] required) {
		this.required = required;
	}

	/**
	 * Returns the required capability that this query is matching against.
	 * @return the required capability that this query is matching against.
	 */
	public IRequiredCapability[] getRequiredCapabilities() {
		return required;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.equinox.p2.query2.Query#isMatch(java.lang.Object)
	 */
	public boolean isMatch(Object object) {
		if (!(object instanceof IInstallableUnit))
			return false;
		IInstallableUnit candidate = (IInstallableUnit) object;
		for (int i = 0; i < required.length; i++)
			if (!candidate.satisfies(required[i]))
				return false;
		return true;
	}
}

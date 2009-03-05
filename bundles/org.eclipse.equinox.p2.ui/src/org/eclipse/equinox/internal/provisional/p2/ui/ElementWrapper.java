/******************************************************************************* 
* Copyright (c) 2009 EclipseSource and others. All rights reserved. This
* program and the accompanying materials are made available under the terms of
* the Eclipse Public License v1.0 which accompanies this distribution, and is
* available at http://www.eclipse.org/legal/epl-v10.html
*
* Contributors:
*   EclipseSource - initial API and implementation
*   IBM Corporation - ongoing development
******************************************************************************/
package org.eclipse.equinox.internal.provisional.p2.ui;

import java.util.*;
import org.eclipse.equinox.internal.provisional.p2.query.Collector;

/**
 * Wraps query results inside corresponding UI elements
 */
public abstract class ElementWrapper {

	private Collection collection = null;

	/**
	 * Transforms a collector returned by a query to a collection
	 * of UI elements
	 */
	public Collection getElements(Collector collector) {
		collection = new ArrayList(collector.size());
		Iterator iter = collector.iterator();
		while (iter.hasNext()) {
			Object o = iter.next();
			if (shouldWrap(o))
				collection.add(wrap(o));
		}
		return getCollection();
	}

	/**
	 * Gets the collection where the elements are being stored.
	 */
	protected Collection getCollection() {
		return collection == null ? Collections.EMPTY_LIST : collection;
	}

	/**
	 * Determines if this object should be accepted and wrapped
	 * by a UI element.  
	 */
	protected boolean shouldWrap(Object o) {
		return true;
	}

	/**
	 * Wraps a single element of the query result inside a UI element.
	 */
	protected abstract Object wrap(Object item);
}

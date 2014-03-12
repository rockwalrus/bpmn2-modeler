/*******************************************************************************
 * Copyright (c) 2011, 2012 Red Hat, Inc.
 *  All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 *
 * @author Bob Brodt
 ******************************************************************************/

package org.eclipse.bpmn2.modeler.core.adapters;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.bpmn2.ExtensionAttributeValue;
import org.eclipse.emf.common.notify.Adapter;
import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EContentAdapter;
import org.eclipse.emf.edit.domain.AdapterFactoryEditingDomain;
import org.eclipse.emf.edit.domain.EditingDomain;
import org.eclipse.emf.edit.domain.IEditingDomainProvider;

/**
 * This adapter will insert an EObject into its container feature when the EObject's
 * content changes. This allows the UI to construct new objects without inserting
 * them into their container unless the user changes some feature in the new object.
 * Thus, an empty EObject is available for use by the UI for rendering only, without
 * creating an EMF transaction, and hence, a useless entry on the undo stack.
 */
public class InsertionAdapter extends EContentAdapter implements IResourceProvider {
	
	protected Resource resource;
	protected EObject object;
	protected EStructuralFeature feature;
	protected EObject value;
	protected Object extensionValue;
	protected EStructuralFeature extensionFeature;
	
	private InsertionAdapter(EObject object, EStructuralFeature feature, EObject value) {
		this(null,object,feature,value, null, null);
	}

	private InsertionAdapter(Resource resource, EObject object, EStructuralFeature feature, EObject value, EStructuralFeature extensionFeature, Object extensionValue) {
		// in order for this to work, the object must be contained in a Resource,
		// the value must NOT YET be contained in a Resource,
		// and the value must be an instance of the feature EType.
//		assert(object.eResource()!=null);
//		assert(value.eResource()==null);
//		assert(feature.getEType().isInstance(value));
		if (resource==null)
			this.resource = object.eResource();
		else
			this.resource = resource;
		this.object = object;
		this.feature = feature;
		this.value = value;
		this.extensionFeature = extensionFeature;
		this.extensionValue = extensionValue;
	}
	
	private InsertionAdapter(EObject object, String featureName, EObject value) {
		this(object, object.eClass().getEStructuralFeature(featureName), value);
	}

	public static EObject add(EObject object, EStructuralFeature feature, EObject value) {
		if (object!=null) {
			value.eAdapters().add(
					new InsertionAdapter(object, feature, value));
		}
		return value;
	}

	public static EObject add(EObject object, EStructuralFeature feature, EObject value, EStructuralFeature extensionFeature, Object extensionValue) {
		if (object!=null) {
			value.eAdapters().add(
					new InsertionAdapter(null, object, feature, value, extensionFeature, extensionValue));
		}
		return value;
	}
	
	public static EObject add(EObject object, String featureName, EObject value) {
		if (object!=null) {
			value.eAdapters().add(
					new InsertionAdapter(object, featureName, value));
		}
		return value;
	}

	public void notifyChanged(Notification notification) {
		if (notification.getNotifier() == value && !(notification.getOldValue() instanceof InsertionAdapter)) {
			// execute if an attribute in the new value has changed
			execute();
		}
		else if (notification.getNotifier()==object && notification.getNewValue()==value) {
			// if the new value has been added to the object, we can remove this adapter
			object.eAdapters().remove(this);
		}
	}

	private void executeChildren(List list) {
		for (Object o : list) {
			if (o instanceof List) {
				executeChildren((List)o);
			}
			else if (o instanceof EObject) {
			    executeIfNeeded((EObject)o);
			}
		}
	}
	
	private void executeChildren(EObject value) {
		// allow other adapters to execute first
		for (EStructuralFeature f : value.eClass().getEAllStructuralFeatures()) {
			try {
				Object v = value.eGet(f);
				if (v instanceof List) {
					executeChildren((List)v);
				}
				else if (v instanceof EObject) {
					executeIfNeeded((EObject)v);
				}
			}
			catch (Exception e) {
				// some getters may throw exceptions - ignore those
			}
		}
		executeIfNeeded(value);
	}
	
	@SuppressWarnings("unchecked")
	public void execute() {
		// if the object into which this value is being added has other adapters execute those first
		executeIfNeeded(object);
		
		// remove this adapter from the value - this adapter is a one-shot deal!
		value.eAdapters().remove(this);

		try {
			Object o = object.eGet(feature);
		}
		catch (Exception e1) {
			try {
				if (value.eClass().getEStructuralFeature(feature.getName())!=null) {
					Object o = value.eGet(feature);
					// this is the inverse add of object into value
					o = value;
					value = object;
					object = (EObject)o;
				}
			}
			catch (Exception e2) {
			}
		}
		// if there are any EObjects contained or referenced by this value, execute those adapters first
		executeChildren(value);
		
		// set the value in the object
		boolean valueChanged = false;
		final EList<EObject> list = feature.isMany() ? (EList<EObject>)object.eGet(feature) : null;
		if (list==null) {
			try {
				valueChanged = object.eGet(feature)!=value;
			}
			catch (Exception e) {
				// feature does not exist, it's a dynamic feature
				valueChanged = true;
			}
		}
		else
			valueChanged = !list.contains(value) || value instanceof ExtensionAttributeValue;
		
		if (valueChanged) {
			ExtendedPropertiesAdapter adapter = ExtendedPropertiesAdapter.adapt(object);
			if (adapter!=null) {
				adapter.getFeatureDescriptor(feature).setValue(value);
			}
		}
	}
	
	public static void executeIfNeeded(EObject value) {
		List<InsertionAdapter> allAdapters = new ArrayList<InsertionAdapter>();
		
		for (Adapter adapter : value.eAdapters()) {
			if (adapter instanceof InsertionAdapter) {
				allAdapters.add((InsertionAdapter)adapter);
			}
		}
		value.eAdapters().removeAll(allAdapters);
		for (InsertionAdapter adapter : allAdapters)
			adapter.execute();
	}
	
	@Override
	public Resource getResource() {
		if (resource==null) {
			Resource res = object.eResource();
			if (res!=null)
				return res;
			InsertionAdapter insertionAdapter = AdapterUtil.adapt(object, InsertionAdapter.class);
			if (insertionAdapter!=null)
				return insertionAdapter.getResource();
		}
		return resource;
	}
	
	@Override
	public void setResource(Resource resource) {
		this.resource = resource;
	}

	public static Resource getResource(EObject object) {
		InsertionAdapter adapter = AdapterUtil.adapt(object, InsertionAdapter.class);
		if (adapter!=null) {
			return adapter.getResource();
		}
		if (object!=null)
			return object.eResource();
		return null;
	}
	
	public EObject getObject() {
		return object;
	}
	
	public EStructuralFeature getFeature() {
		return feature;
	}
	
	public EObject getValue() {
		return value;
	}

	@Override
	public EditingDomain getEditingDomain() {
		getResource();
		if (resource!=null)
			return AdapterFactoryEditingDomain.getEditingDomainFor(resource);
		return null;
	}
}
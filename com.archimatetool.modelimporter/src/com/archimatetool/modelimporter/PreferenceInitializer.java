/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package com.archimatetool.modelimporter;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;




/**
 * Class used to initialize default preference values
 * 
 * @author Phillip Beauvoir
 */
public class PreferenceInitializer extends AbstractPreferenceInitializer
implements IPreferenceConstants {

    @Override
    public void initializeDefaultPreferences() {
        IPreferenceStore store = ImporterPlugin.getInstance().getPreferenceStore();
        
        store.setDefault(IMPORTER_PREFS_UPDATE, false);
        store.setDefault(IMPORTER_PREFS_UPDATE_ALL, false);
        store.setDefault(IMPORTER_PREFS_SHOW_STATUS_DIALOG, true);
    }
}

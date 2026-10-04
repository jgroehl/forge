package forge.screens.home.settings;

import javax.swing.SwingUtilities;

import forge.gui.framework.ICDoc;

/**
 * Controls the AI settings submenu in the home UI.
 */
public enum CSubmenuAiSettings implements ICDoc {
    SINGLETON_INSTANCE;

    @Override
    public void register() {
    }

    @Override
    public void initialize() {
    }

    @Override
    public void update() {
        SwingUtilities.invokeLater(VSubmenuAiSettings.SINGLETON_INSTANCE::refreshFromPrefs);
    }
}

package forge.screens.home.settings;

import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

import javax.swing.JPanel;

import forge.gui.framework.DragCell;
import forge.gui.framework.DragTab;
import forge.gui.framework.EDocID;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.screens.home.EMenuGroup;
import forge.screens.home.IVSubmenu;
import forge.screens.home.VHomeUI;
import forge.toolbox.FComboBox;
import forge.toolbox.FLabel;
import forge.toolbox.FTextField;
import forge.util.Localizer;
import net.miginfocom.swing.MigLayout;

/**
 * Settings for the external LLM agent used by "LLM AI" players.
 */
public enum VSubmenuAiSettings implements IVSubmenu<CSubmenuAiSettings> {
    SINGLETON_INSTANCE;

    private static final String TEMPLATE_FULL = "full";
    private static final String TEMPLATE_MINIMAL = "minimal";

    private final Localizer localizer = Localizer.getInstance();
    private DragCell parentCell;
    private final DragTab tab = new DragTab(localizer.getMessage("lblAiSettings"));

    private final JPanel pnlSettings = new JPanel();
    private final FTextField txtAddress = new FTextField.Builder().build();
    private final FTextField txtModel = new FTextField.Builder().build();
    private final FComboBox<String> cbTemplate = new FComboBox<>();

    VSubmenuAiSettings() {
        pnlSettings.setOpaque(false);
        pnlSettings.setLayout(new MigLayout("insets 20px, gap 10px", "[][grow, fill]"));

        cbTemplate.addItem(localizer.getMessage("lblLlmPromptFull"));
        cbTemplate.addItem(localizer.getMessage("lblLlmPromptMinimal"));

        addRow("lblLlmAgentAddress", "nlLlmAgentAddress", txtAddress);
        addRow("lblLlmAgentModel", "nlLlmAgentModel", txtModel);
        addRow("lblLlmPromptTemplate", "nlLlmPromptTemplate", cbTemplate);

        txtAddress.addFocusListener(saveOnFocusLost(FPref.LLM_AGENT_URL, txtAddress));
        txtModel.addFocusListener(saveOnFocusLost(FPref.LLM_AGENT_MODEL, txtModel));
        cbTemplate.addActionListener(e -> {
            final String value = cbTemplate.getSelectedIndex() == 1 ? TEMPLATE_MINIMAL : TEMPLATE_FULL;
            FModel.getPreferences().setPref(FPref.LLM_PROMPT_TEMPLATE, value);
            FModel.getPreferences().save();
        });

        refreshFromPrefs();
    }

    private void addRow(final String labelKey, final String tooltipKey, final javax.swing.JComponent field) {
        final FLabel label = new FLabel.Builder().text(localizer.getMessage(labelKey) + ":").build();
        field.setToolTipText(localizer.getMessage(tooltipKey));
        pnlSettings.add(label, "h 30px!");
        pnlSettings.add(field, "h 30px!, wrap");
    }

    private static FocusAdapter saveOnFocusLost(final FPref pref, final FTextField field) {
        return new FocusAdapter() {
            @Override public void focusLost(final FocusEvent e) {
                FModel.getPreferences().setPref(pref, field.getText().trim());
                FModel.getPreferences().save();
            }
        };
    }

    public void refreshFromPrefs() {
        txtAddress.setText(FModel.getPreferences().getPref(FPref.LLM_AGENT_URL));
        txtModel.setText(FModel.getPreferences().getPref(FPref.LLM_AGENT_MODEL));
        cbTemplate.setSelectedIndex(
                TEMPLATE_MINIMAL.equalsIgnoreCase(FModel.getPreferences().getPref(FPref.LLM_PROMPT_TEMPLATE)) ? 1 : 0);
    }

    @Override
    public void populate() {
        VHomeUI.SINGLETON_INSTANCE.getPnlDisplay().removeAll();
        VHomeUI.SINGLETON_INSTANCE.getPnlDisplay().setLayout(new MigLayout("insets 0, gap 0"));
        VHomeUI.SINGLETON_INSTANCE.getPnlDisplay().add(pnlSettings, "w 98%!, h 98%!, gap 1% 0 1% 0");
        VHomeUI.SINGLETON_INSTANCE.getPnlDisplay().repaintSelf();
        VHomeUI.SINGLETON_INSTANCE.getPnlDisplay().revalidate();
    }

    @Override
    public EMenuGroup getGroupEnum() {
        return EMenuGroup.SETTINGS;
    }

    @Override
    public String getMenuTitle() {
        return localizer.getMessage("lblAiSettings");
    }

    @Override
    public EDocID getItemEnum() {
        return EDocID.HOME_AI_SETTINGS;
    }

    @Override
    public EDocID getDocumentID() {
        return EDocID.HOME_AI_SETTINGS;
    }

    @Override
    public DragTab getTabLabel() {
        return tab;
    }

    @Override
    public CSubmenuAiSettings getLayoutControl() {
        return CSubmenuAiSettings.SINGLETON_INSTANCE;
    }

    @Override
    public void setParentCell(final DragCell cell0) {
        this.parentCell = cell0;
    }

    @Override
    public DragCell getParentCell() {
        return parentCell;
    }
}

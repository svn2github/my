/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2011 OpenConcerto, by ILM Informatique. All rights reserved.
 * 
 * The contents of this file are subject to the terms of the GNU General Public License Version 3
 * only ("GPL"). You may not use this file except in compliance with the License. You can obtain a
 * copy of the License at http://www.gnu.org/licenses/gpl-3.0.html See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each file.
 */
 
 /*
 * Créé le 26 mars 2012
 */
package org.openconcerto.erp.core.humanresources.employe.panel;

import org.openconcerto.erp.core.common.ui.DeviseCellEditor;
import org.openconcerto.erp.core.common.ui.DeviseNiceTableCellRenderer;
import org.openconcerto.erp.core.common.ui.DeviseNumericCellEditor;
import org.openconcerto.erp.core.common.ui.DeviseTableCellRenderer;
import org.openconcerto.sql.Configuration;
import org.openconcerto.sql.model.SQLRowValues;
import org.openconcerto.sql.model.SQLSelect;
import org.openconcerto.sql.model.SQLTable;
import org.openconcerto.sql.model.Where;
import org.openconcerto.ui.DefaultGridBagConstraints;
import org.openconcerto.ui.JLabelBold;
import org.openconcerto.ui.table.AlternateTableCellRenderer;
import org.openconcerto.utils.ExceptionHandler;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

public class ObjectifEditPanel extends JPanel {

    private int idCommercial;
    private final SQLTable tableObjectif = Configuration.getInstance().getRoot().findTable("OBJECTIF_COMMERCIAL");
    private final ListAnneeModel<Integer> listModel = new ListAnneeModel<Integer>();
    private final ObjectifTableModel tableModel = new ObjectifTableModel();

    public ObjectifEditPanel(int idCommercial) {
        super(new GridBagLayout());
        this.idCommercial = idCommercial;
        uiInit();
        setOpaque(false);
    }

    public void setIdCommercial(int idCommercial) {
        this.idCommercial = idCommercial;
        listModel.loadData(idCommercial);
        tableModel.loadData(this.idCommercial, 0);
    }

    private void uiInit() {

        GridBagConstraints c = new DefaultGridBagConstraints();

        this.add(new JLabelBold("Année"), c);
        c.gridx++;
        this.add(new JLabelBold("Objectifs mensuels"), c);
        listModel.loadData(idCommercial);
        final JList jList = new JList(listModel);
        c.gridy++;
        c.gridx = 0;
        c.weighty = 1.0;
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 0.2;
        this.add(jList, c);

        c.gridx++;
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 0.8;

        final JTable table = new JTable(tableModel);

        for (int i = 1; i < 4; i += 2) {
            final DeviseNiceTableCellRenderer rend = new DeviseNiceTableCellRenderer();
            final DeviseCellEditor cellEditor = new DeviseCellEditor();
            table.getColumnModel().getColumn(i).setCellEditor(cellEditor);
            table.getColumnModel().getColumn(i).setCellRenderer(rend);
        }
        table.getColumnModel().getColumn(2).setCellEditor(new DeviseNumericCellEditor(tableObjectif.getField("POURCENT_MARGE")));
        table.getColumnModel().getColumn(2).setCellRenderer(new DeviseTableCellRenderer());

        AlternateTableCellRenderer.UTILS.setAllColumns(table);
        this.add(new JScrollPane(table), c);
        jList.addListSelectionListener(new ListSelectionListener() {

            @Override
            public void valueChanged(ListSelectionEvent e) {
                Integer annee = listModel.getElementAt(jList.getSelectedIndex());
                tableModel.loadData(idCommercial, annee);
            }
        });
        c.gridy++;
        c.gridx = 0;
        c.weightx = 0;
        c.weighty = 0;
        c.fill = GridBagConstraints.NONE;
        this.add(new JButton(new AbstractAction("Ajouter une année") {

            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    addYear();
                } catch (SQLException e1) {
                    ExceptionHandler.handle("Erreur lors de la création d'une nouvelle année", e1);
                }
            }
        }), c);

    }

    private void addYear() throws SQLException {
        // FIXME à mettre dans une transaction
        // FIXME remove SQL from EDT
        SQLSelect sel = new SQLSelect();
        sel.addSelect(tableObjectif.getField("ANNEE"), "MAX");
        sel.setWhere(new Where(tableObjectif.getField("ID_COMMERCIAL"), "=", this.idCommercial));

        Object anneeObject = (Object) Configuration.getInstance().getBase().getDataSource().executeScalar(sel.asString());
        int annee;
        if (anneeObject == null) {
            annee = Calendar.getInstance().get(Calendar.YEAR);
        } else {
            annee = (Integer) anneeObject;
            annee++;
        }

        List<String> s = Arrays.asList("Janvier", "Février", "Mars", "Avril", "Mai", "Juin", "Juillet", "Août", "Septembre", "Octobre", "Novembre", "Décembre");
        for (String string : s) {
            SQLRowValues rowVals = new SQLRowValues(tableObjectif);
            rowVals.put("ANNEE", annee);
            rowVals.put("MOIS", string);
            rowVals.put("MARGE_HT", Long.valueOf(0));
            rowVals.put("POURCENT_MARGE", BigDecimal.ZERO);
            rowVals.put("CHIFFRE_AFFAIRE", Long.valueOf(0));
            rowVals.put("ID_COMMERCIAL", this.idCommercial);
            rowVals.insert();
        }
        listModel.addElement(annee);
    }

}

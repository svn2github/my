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
 
 package org.openconcerto.erp.generationDoc;

import org.openconcerto.sql.model.SQLRow;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public abstract class AbstractSheetXml extends SheetXml {

    public AbstractSheetXml(SQLRow row) {
        this.row = row;
    }

    public final Future<File> genere(final boolean visu, final boolean impression) {
        Callable<File> c = new Callable<File>() {
            @Override
            public File call() throws Exception {
                try {
                    String modele = getModele();
                    final String modeleFinal = modele;
                    try {
                        OOgenerationXML.getOOTemplate(modele, getRowLanguage());
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(new Runnable() {

                            @Override
                            public void run() {
                                // TODO Raccord de méthode auto-généré
                                JOptionPane.showMessageDialog(null, "Impossible de trouver le modele " + modeleFinal + ". \n Le modéle par défaut sera utilisé!");
                            }
                        });
                        modele = getDefaultModele();
                    }
                    File fGen = OOgenerationXML.genere(modele, AbstractSheetXml.this.locationOO, getFileName(), AbstractSheetXml.this.row, getRowLanguage());
                    AbstractSheetXml.this.f = fGen;
                    useOO(fGen, visu, impression, getFileName());
                    return fGen;
                } catch (Exception e) {
                    DEFAULT_HANDLER.uncaughtException(null, e);
                    // rethrow exception so that the unsuspecting caller can use this as the
                    // original task
                    throw e;
                } catch (Throwable e) {
                    DEFAULT_HANDLER.uncaughtException(null, e);
                    return null;
                }

            }
        };
        return runnableQueue.submit(c);
    }
}

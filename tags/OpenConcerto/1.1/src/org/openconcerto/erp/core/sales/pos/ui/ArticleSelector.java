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
 
 package org.openconcerto.erp.core.sales.pos.ui;

import org.openconcerto.erp.core.sales.pos.model.Article;
import org.openconcerto.erp.core.sales.pos.model.Categorie;
import org.openconcerto.ui.touch.ScrollableList;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JPanel;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

public class ArticleSelector extends JPanel implements ListSelectionListener, CaisseListener {
    private ArticleModel model;
    private ScrollableList list;
    private StatusBar comp;
    private CaisseControler controller;

    ArticleSelector(final CaisseControler controller) {
        this.controller = controller;
        this.controller.addCaisseListener(this);

        this.setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.BOTH;
        comp = new StatusBar();
        comp.setTitle("Articles");
        this.add(comp, c);

        c.weighty = 1;
        c.gridy++;
        model = new ArticleModel();
        model.setCategorie(null);

        final Font f = new Font("Arial", Font.PLAIN, 24);
        list = new ScrollableList(model) {
            @Override
            public void paint(Graphics g) {

                super.paint(g);
                g.setColor(Color.GRAY);
                g.drawLine(0, 0, 0, this.getHeight());
            }

            @Override
            public void paintCell(Graphics g, Object object, int index, boolean isSelected, int posY) {
                g.setFont(f);

                if (isSelected) {
                    g.setColor(new Color(232, 242, 254));
                } else {
                    g.setColor(Color.WHITE);
                }
                g.fillRect(0, posY, getWidth(), getCellHeight());

                //
                g.setColor(Color.GRAY);
                g.drawLine(0, posY + this.getCellHeight() - 1, this.getWidth(), posY + this.getCellHeight() - 1);

                if (isSelected) {
                    g.setColor(Color.BLACK);
                } else {
                    g.setColor(Color.GRAY);
                }
                ((Graphics2D) g).setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Article article = (Article) object;
                String label = article.getName();
                if (label.length() > 14) {
                    label = label.substring(0, 14) + "...";
                }

                String euro = TicketCellRenderer.centsToString(article.getPriceInCents()) + "€";

                int wEuro = (int) g.getFontMetrics().getStringBounds(euro, g).getWidth();
                g.drawString(label, 10, posY + 39);
                g.drawString(euro, getWidth() - 5 - wEuro, posY + 39);

            }
        };

        list.setFixedCellHeight(64);
        list.setOpaque(true);
        this.add(list, c);
        list.addListSelectionListener(this);

        comp.setModel(model);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {

                int nb = e.getClickCount();
                System.out.println(nb);
                if (nb > 1) {
                    Object sel = list.getSelectedValue();
                    if (sel != null) {
                        Article article = (Article) sel;
                        controller.incrementArticle(article);

                        controller.setArticleSelected(article);

                    }
                }
            }
        });
    }

    @Override
    public void valueChanged(ListSelectionEvent e) {
        Object sel = list.getSelectedValue();
        if (sel != null && !e.getValueIsAdjusting()) {
            Article article = (Article) sel;
            controller.addArticle(article);

            controller.setArticleSelected(article);

        }
    }

    public ArticleModel getModel() {
        return this.model;
    }

    @Override
    public void caisseStateChanged() {

        final Article articleSelected = controller.getArticleSelected();
        System.out.println("ArticleSelector.caisseStateChanged():" + articleSelected);
        if (articleSelected == null) {
            list.clearSelection();
            return;

        }

        Object selectedValue = null;
        try {
            selectedValue = list.getSelectedValue();
        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
        }
        if (articleSelected != null && !articleSelected.equals(selectedValue)) {
            Categorie c = articleSelected.getCategorie();
            model.setCategorie(c);
            list.setSelectedValue(articleSelected, true);
        }

    }

}

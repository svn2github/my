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
 
 package org.openconcerto.sql.model.graph;

import org.openconcerto.sql.model.DBRoot;
import org.openconcerto.sql.model.SQLField;
import org.openconcerto.sql.model.SQLTable;
import org.openconcerto.utils.CollectionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Un chemin dans une base de donnée. Un chemin est composé de tables, d'autre part les tables sont
 * relées entre elle par un sous-ensemble des champs les reliant dans le graphe.
 * 
 * @author ILM Informatique 4 oct. 2004
 */
public class Path {

    /**
     * Crée un chemin à partir d'une liste de String.
     * 
     * @param base la base du chemin.
     * @param path le chemin sous forme de String
     * @return le chemin correspondant.
     * @see #add(String)
     */
    static public Path create(DBRoot base, List<String> path) {
        if (path.size() == 0)
            throw new IllegalArgumentException("path is empty");

        // le premier doit etre une table car un champ est ambigu.
        final SQLTable first = base.getTable(path.get(0));
        if (first == null)
            throw new IllegalArgumentException("first item must be a table");
        Path res = new Path(first);
        for (int i = 1; i < path.size(); i++) {
            res.add(path.get(i));
        }

        return res;
    }

    /**
     * Crée un chemin à partir d'une liste de Link.
     * 
     * @param first la premiere table.
     * @param links les liens.
     * @return le chemin correspondant, ou <code>null</code> si links est <code>null</code>.
     */
    static public Path create(SQLTable first, List<Link> links) {
        // besoin de spécifier le premier, eg BATIMENT.ID_SITE => quel sens ?
        if (links == null)
            return null;
        final Path res = new Path(first);
        for (final Link link : links) {
            res.add(link);
        }
        return res;
    }

    private final DBRoot base;
    private final List<SQLTable> tables;
    private final List<Step> fields;
    // after profiling: doing getStep().iterator().next() costs a lot
    private final List<SQLField> singleFields;

    public Path(SQLTable start) {
        this.tables = new ArrayList<SQLTable>();
        this.fields = new ArrayList<Step>();
        this.singleFields = new ArrayList<SQLField>();
        this.base = start.getDBRoot();
        this.tables.add(start);
    }

    public Path(Path p) {
        this.tables = new ArrayList<SQLTable>(p.tables);
        // ok since Step is immutable
        this.fields = new ArrayList<Step>(p.fields);
        this.singleFields = new ArrayList<SQLField>(p.singleFields);
        this.base = p.base;
    }

    public final Path reverse() {
        final Path res = new Path(getLast());
        for (int i = this.fields.size() - 1; i >= 0; i--) {
            res.add(this.fields.get(i).reverse());
        }
        return res;
    }

    /**
     * La longueur de ce chemin.
     * 
     * @return la longueur de ce chemin.
     */
    public int length() {
        return this.fields.size();
    }

    public SQLTable getFirst() {
        return this.getTable(0);
    }

    public SQLTable getLast() {
        return this.getTable(this.tables.size() - 1);
    }

    /**
     * La table se trouvant à la position demandée dans ce chemin.
     * 
     * @param i l'index, entre 0 et length() inclus.
     * @return la table se trouvant à la position demandée.
     */
    public SQLTable getTable(int i) {
        return this.tables.get(i);
    }

    public Path minusFirst() {
        return this.subPath(1, this.length());
    }

    public Path minusLast() {
        return this.subPath(0, this.length() - 1);
    }

    public Path subPath(int fromIndex) {
        return this.subPath(fromIndex, this.length());
    }

    /**
     * Returns a copy of the portion of this path between the specified <tt>fromIndex</tt>,
     * inclusive, and <tt>toIndex</tt>, exclusive. (If <tt>fromIndex</tt> and <tt>toIndex</tt> are
     * equal, the returned list is empty). Indices can be negative to count from the end, see
     * {@link CollectionUtils#getValidIndex(List, int)}
     * 
     * @param fromIndex low endpoint (inclusive) of the subPath
     * @param toIndex high endpoint (exclusive) of the subPath
     * @return the specified range within this path
     */
    public Path subPath(int fromIndex, int toIndex) {
        fromIndex = CollectionUtils.getValidIndex(this.fields, fromIndex);
        toIndex = CollectionUtils.getValidIndex(this.fields, toIndex);
        final Path p = new Path(this.getTable(fromIndex));
        // +1 since we've passed the first to the ctor
        // +1 since there's one more table than fields
        p.tables.addAll(this.tables.subList(fromIndex + 1, toIndex + 1));
        p.fields.addAll(this.fields.subList(fromIndex, toIndex));
        p.singleFields.addAll(this.singleFields.subList(fromIndex, toIndex));
        return p;
    }

    public Path justFirst() {
        final Path p = new Path(this.getFirst());
        p.add(this.fields.get(0));
        return p;
    }

    public List<SQLTable> getTables() {
        return Collections.unmodifiableList(this.tables);
    }

    /**
     * Append <code>p</code> to this path.
     * 
     * @param p the path to append.
     * @throws IllegalArgumentException if <code>p</code> doesn't begin where this ends.
     */
    public final void append(Path p) {
        if (this.getLast() != p.getFirst())
            throw new IllegalArgumentException("this ends at " + this.getLast() + " while the other begins at " + p.getFirst());
        this.fields.addAll(p.fields);
        this.singleFields.addAll(p.singleFields);
        this.tables.addAll(p.tables.subList(1, p.tables.size()));
    }

    private void add(Step step) {
        assert step.getFrom() == this.getLast() : "broken path";
        this.fields.add(step);
        this.singleFields.add(step.getSingleField());
        this.tables.add(step.getTo());
    }

    /**
     * Ajoute un maillon a la chaine. item doit être soit le nom d'une table, soit le nom complet
     * d'un champ (TABLE.FIELD_NAME).
     * 
     * @param item le nouveau maillon.
     */
    public void add(String item) {
        int dot = item.indexOf('.');
        if (dot < 0) {
            add(this.base.getTable(item));
        } else {
            add(this.base.getDesc(item, SQLField.class));
        }
    }

    /**
     * Add a table at the end of the path. NOTE: the step will be composed of all the foreign fields
     * between {@link #getLast()} and <code>destTable</code>.
     * 
     * @param destTable the table to add.
     * @throws IllegalArgumentException if <code>destTable</code> has no foreign fields between
     *         itself and the current end of this path.
     */
    public final void add(final SQLTable destTable) {
        this.add(Step.create(getLast(), destTable));
    }

    /**
     * Add a step to the path.
     * 
     * @param fField a foreign field.
     * @throws IllegalArgumentException if <code>fField</code> is not a foreign field or if neither
     *         of its ends are the current end of this path.
     */
    public final void add(final SQLField fField) {
        this.add(fField, null);
    }

    /**
     * Add a step to the path.
     * 
     * @param fField a foreign field.
     * @param direction <code>true</code> to cross from the source of <code>fField</code> to its
     *        destination, <code>null</code> to infer it.
     * @throws IllegalArgumentException if <code>fField</code> is not a foreign field or if neither
     *         of its ends are the current end of this path.
     */
    public final void add(final SQLField fField, final Boolean direction) {
        this.add(Step.create(getLast(), fField, direction));
    }

    public final void addFields(final Collection<SQLField> fields) {
        final Set<Link> links = new HashSet<Link>(fields.size());
        final DatabaseGraph graph = getFirst().getDBSystemRoot().getGraph();
        for (final SQLField f : fields) {
            links.add(graph.getForeignLink(f));
        }
        this.add(links);
    }

    /**
     * Add a step to the path.
     * 
     * @param links links from the current end of this path to another table.
     * @throws IllegalArgumentException if <code>links</code> are not all between the current end
     *         and the same other table.
     */
    public final void add(final Collection<Link> links) {
        this.add(Step.create(getLast(), links));
    }

    /**
     * Ajoute un maillon a la chaine.
     * 
     * @param item un lien.
     * @throws IllegalArgumentException si aucune des extremités de item n'est connectée à ce
     *         chemin.
     */
    public void add(Link item) {
        this.add(item.getLabel());
    }

    /**
     * The step connecting the table i to i+1.
     * 
     * @param i the step asked, from 0 to {@link #length()} -1.
     * @return the requested step.
     */
    public final Step getStep(int i) {
        return this.fields.get(i);
    }

    /**
     * The fields connecting the table i to i+1.
     * 
     * @param i the step asked, from 0 to {@link #length()} -1.
     * @return the fields connecting the table i to i+1.
     */
    public final Set<SQLField> getStepFields(int i) {
        return this.fields.get(i).getFields();
    }

    /**
     * Return the field connecting the table i to i+1, or <code>null</code> if there is more than
     * one (ie {@link #isSingleLink()} is <code>false</code>).
     * 
     * @param i the step asked, from 0 to {@link #length()} -1.
     * @return the field connecting the table i to i+1, or <code>null</code> if there is more than
     *         one.
     */
    public SQLField getSingleStep(int i) {
        return this.singleFields.get(i);
    }

    /**
     * Return the fields connecting the tables.
     * 
     * @return a list of fields and <code>null</code>s.
     * @see #getSingleStep(int)
     */
    public final List<SQLField> getSingleSteps() {
        return Collections.unmodifiableList(this.singleFields);
    }

    /**
     * Whether there's one and only one field between each table.
     * 
     * @return <code>true</code> if there's exactly 1 field between each table.
     */
    public boolean isSingleLink() {
        for (final SQLField step : this.singleFields) {
            if (step == null)
                return false;
        }
        return true;
    }

    /**
     * Whether the step <code>i</code> is backwards.
     * 
     * @param i the index of the step.
     * @return <code>true</code> if all fields are backwards (eg going from CONTACT to SITE with
     *         ID_CONTACT_CHEF and ID_CONTACT_BUREAU), <code>null</code> if mixed (eg going from
     *         CONTACT to SITE with SITE.ID_CONTACT_CHEF and CONTACT.ID_SITE), <code>false</code>
     *         otherwise.
     */
    public final Boolean isBackwards(final int i) {
        final Boolean foreign = this.fields.get(i).isForeign();
        return foreign == null ? null : !foreign;
    }

    /**
     * Whether all steps are in the same (non-mixed) direction.
     * 
     * @return <code>null</code> if at least one step is mixed, <code>true</code> if all steps are
     *         in the same direction (be it backwards or forwards).
     */
    public final Boolean isSingleDirection() {
        return this.isSingleDirection(null);
    }

    /**
     * Whether all steps are in the passed direction.
     * 
     * @param foreign <code>true</code> if all steps should be forwards.
     * @return <code>null</code> if at least one step is mixed, <code>true</code> if all steps are
     *         in the same direction as <code>foreign</code>.
     */
    public final Boolean isSingleDirection(final boolean foreign) {
        return this.isSingleDirection(Boolean.valueOf(foreign));
    }

    // don't expose null since it could be confused as meaning "all steps have mixed directions"
    private final Boolean isSingleDirection(final Boolean foreign) {
        Boolean dir = foreign;
        for (final Step s : this.fields) {
            final Boolean stepIsForeign = s.isForeign();
            if (stepIsForeign == null)
                return null;
            else if (dir == null)
                dir = stepIsForeign;
            else if (!dir.equals(stepIsForeign))
                return false;
        }
        return true;
    }

    public String toString() {
        return "Path\n\tTables: " + this.tables + "\n\tLinks:" + this.fields;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Path) {
            final Path o = (Path) obj;
            // no need to compare tables, starting from the same point the same fields lead to the
            // same table
            return this.base.equals(o.base) && this.getFirst().equals(o.getFirst()) && this.fields.equals(o.fields);
        } else
            return false;
    }

    /**
     * Returns a hash code value for this path. NOTE: as with any list, the hashCode change when its
     * items do.
     * 
     * @return a hash code value for this object.
     * @see java.util.List#hashCode()
     */
    @Override
    public int hashCode() {
        return this.fields.hashCode();
    }

    public boolean startsWith(Path other) {
        return this.length() >= other.length() && this.subPath(0, other.length()).equals(other);
    }
}

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
 
 package org.openconcerto.sql.preferences;

import org.openconcerto.sql.model.DBRoot;
import org.openconcerto.sql.model.IResultSetHandler;
import org.openconcerto.sql.model.SQLDataSource;
import org.openconcerto.sql.model.SQLName;
import org.openconcerto.sql.model.SQLRow;
import org.openconcerto.sql.model.SQLRowValues;
import org.openconcerto.sql.model.SQLSelect;
import org.openconcerto.sql.model.SQLSyntax;
import org.openconcerto.sql.model.SQLTable;
import org.openconcerto.sql.model.Where;
import org.openconcerto.sql.utils.SQLCreateTable;
import org.openconcerto.sql.utils.SQLUtils;
import org.openconcerto.sql.utils.SQLUtils.SQLFactory;
import org.openconcerto.utils.CollectionUtils;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import org.apache.commons.dbutils.ResultSetHandler;

/**
 * Preferences backed by SQL tables.
 * 
 * @author Sylvain CUAZ
 */
public class SQLPreferences extends AbstractPreferences {

    private static final String PREF_NODE_TABLENAME = "PREF_NODE";
    private static final String PREF_VALUE_TABLENAME = "PREF_VALUE";

    static public SQLTable getPrefTable(final DBRoot root) throws SQLException {
        if (!root.contains(PREF_VALUE_TABLENAME)) {
            final SQLDataSource ds = root.getDBSystemRoot().getDataSource();
            SQLUtils.executeAtomic(ds, new SQLFactory<Object>() {
                @Override
                public Object create() throws SQLException {
                    final SQLCreateTable createNodeT = new SQLCreateTable(root, PREF_NODE_TABLENAME);
                    // don't need ORDER and ARCHIVE
                    createNodeT.setPlain(true);
                    createNodeT.addColumn(SQLSyntax.ID_NAME, createNodeT.getSyntax().getPrimaryIDDefinition());
                    // cannot use addForeignColumn() since it's a self-reference
                    createNodeT.addColumn("ID_PARENT", createNodeT.getSyntax().getIDType() + " NULL");
                    createNodeT.addVarCharColumn("NAME", Preferences.MAX_NAME_LENGTH);

                    createNodeT.addForeignConstraint("ID_PARENT", new SQLName(createNodeT.getName()), SQLSyntax.ID_NAME);
                    createNodeT.addUniqueConstraint("uniqNamePerParent", Arrays.asList("ID_PARENT", "NAME"));

                    final SQLCreateTable createValueT = new SQLCreateTable(root, PREF_VALUE_TABLENAME);
                    createValueT.setPlain(true);
                    createValueT.addColumn("ID_NODE", createValueT.getSyntax().getIDType() + " NOT NULL");
                    createValueT.addVarCharColumn("NAME", Preferences.MAX_KEY_LENGTH);
                    createValueT.addVarCharColumn("VALUE", Preferences.MAX_VALUE_LENGTH);
                    // unique name per node
                    createValueT.setPrimaryKey("ID_NODE", "NAME");
                    createValueT.addForeignConstraint("ID_NODE", new SQLName(createNodeT.getName()), SQLSyntax.ID_NAME);

                    for (final SQLCreateTable ct : new SQLCreateTable[] { createNodeT, createValueT }) {
                        ds.execute(ct.asString());
                        SQLTable.setUndefID(root.getSchema(), ct.getName(), null);
                    }
                    root.getSchema().updateVersion();
                    return null;
                }
            });
            root.refetch();
        }
        return root.getTable(PREF_VALUE_TABLENAME);
    }

    private final SQLTable prefT;
    private final SQLTable nodeT;
    // values from the DB
    private Map<String, String> values;
    // values changed in-memory (not yet committed)
    private final Map<String, String> changedValues;
    // values removed in-memory (not yet committed)
    private final Set<String> removedKeys;
    private SQLRow node;

    // root node
    public SQLPreferences(DBRoot db) {
        this(null, "", db);
    }

    private SQLPreferences(SQLPreferences parent, String name, DBRoot db) {
        super(parent, name);
        if (db == null)
            throw new IllegalArgumentException("Missing DBRoot");

        this.prefT = db.getTable(PREF_VALUE_TABLENAME);
        this.nodeT = this.prefT.getForeignTable("ID_NODE");

        this.values = null;
        this.changedValues = new HashMap<String, String>();
        this.removedKeys = new HashSet<String>();
        this.node = null;
    }

    private final SQLDataSource getDS() {
        return this.prefT.getDBSystemRoot().getDataSource();
    }

    private Object execute(final String sel, ResultSetHandler rsh) {
        // don't use the cache, our superclass and us have already a cache system
        // plus this would require to fire when modifying the table
        return getDS().execute(sel, new IResultSetHandler(rsh, false));
    }

    public final SQLRow getNode() {
        try {
            return this.getNode(false);
        } catch (SQLException e) {
            // shouldn't happen since we don't want to access the DB
            throw new IllegalStateException(e);
        }
    }

    public final SQLRow getNode(final boolean create) throws SQLException {
        if (this.node == null) {
            final SQLPreferences parent = (SQLPreferences) parent();
            final Where parentW;
            if (parent == null) {
                parentW = Where.isNull(this.nodeT.getField("ID_PARENT"));
            } else {
                final SQLRow parentNode = parent.getNode(create);
                parentW = parentNode == null ? null : new Where(this.nodeT.getField("ID_PARENT"), "=", parentNode.getID());
            }
            if (parentW == null) {
                // our parent is not in the DB, we can't
                this.node = null;
            } else {
                final SQLSelect sel = new SQLSelect(this.nodeT.getBase()).addSelectStar(this.nodeT);
                sel.setWhere(parentW.and(new Where(this.nodeT.getField("NAME"), "=", name())));

                @SuppressWarnings("unchecked")
                final Map<String, ?> m = (Map<String, ?>) execute(sel.asString(), SQLDataSource.MAP_HANDLER);
                this.node = m == null ? null : new SQLRow(this.nodeT, m);
            }
            if (this.node == null && create) {
                final SQLRowValues insVals = new SQLRowValues(this.nodeT);
                insVals.put("ID_PARENT", parent == null ? null : parent.getNode(create).getID());
                insVals.put("NAME", name());
                this.node = insVals.insert();
            }
        }
        return this.node;
    }

    public final Map<String, String> getValues() {
        if (this.values == null) {
            this.values = new HashMap<String, String>();
            final SQLRow node = getNode();
            if (node != null) {
                final SQLSelect sel = new SQLSelect(this.prefT.getBase()).addSelectStar(this.prefT);
                sel.setWhere(new Where(this.prefT.getField("ID_NODE"), "=", node.getID()));

                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> l = (List<Map<String, Object>>) execute(sel.asString(), SQLDataSource.MAP_LIST_HANDLER);
                for (final Map<String, Object> r : l) {
                    this.values.put(r.get("NAME").toString(), r.get("VALUE").toString());
                }
            }
        }
        return this.values;
    }

    @Override
    protected void putSpi(String key, String value) {
        this.changedValues.put(key, value);
        this.removedKeys.remove(key);
    }

    @Override
    protected void removeSpi(String key) {
        this.removedKeys.add(key);
        this.changedValues.remove(key);
    }

    @Override
    protected String getSpi(String key) {
        if (this.removedKeys.contains(key))
            return null;
        else if (this.changedValues.containsKey(key))
            return this.changedValues.get(key);
        else
            return this.getValues().get(key);
    }

    // null means delete all
    private void deleteValues(final Set<String> keys) {
        final SQLRow node = this.getNode();
        if (node != null) {
            final String keysW;
            if (keys == null)
                keysW = "";
            else
                keysW = " and " + new Where(this.prefT.getField("NAME"), keys).getClause();

            getDS().execute("DELETE FROM " + this.prefT.getSQLName().quote() + " where \"ID_NODE\" = " + node.getID() + keysW);
        }
    }

    @Override
    protected void removeNodeSpi() throws BackingStoreException {
        try {
            final SQLRow node = this.getNode();
            if (node != null) {
                deleteValues(null);
                getDS().execute("DELETE FROM " + this.nodeT.getSQLName().quote() + " where \"ID\" = " + node.getID());
                this.node = null;
            }
        } catch (Exception e) {
            throw new BackingStoreException(e);
        }
        assert this.node == null;
        this.values = null;
        this.removedKeys.clear();
        this.changedValues.clear();
    }

    @Override
    protected String[] keysSpi() throws BackingStoreException {
        try {
            final Set<String> committedKeys = this.getValues().keySet();
            final Set<String> res;
            if (this.removedKeys.isEmpty() && this.changedValues.isEmpty()) {
                res = committedKeys;
            } else {
                res = new HashSet<String>(committedKeys);
                res.removeAll(this.removedKeys);
                res.addAll(this.changedValues.keySet());
            }
            return res.toArray(new String[res.size()]);
        } catch (Exception e) {
            throw new BackingStoreException(e);
        }
    }

    @Override
    protected String[] childrenNamesSpi() throws BackingStoreException {
        try {
            final SQLRow node = this.getNode();
            if (node == null) {
                // OK since "This method need not return the names of any nodes already cached"
                // so if we call pref.node("a/b/c") with no existing nodes this still works
                return new String[0];
            }

            final int nodeID = node.getID();
            final SQLSelect sel = new SQLSelect(this.nodeT.getBase()).addSelect(this.nodeT.getField("NAME"));
            final Where w = new Where(this.nodeT.getField("ID_PARENT"), "=", nodeID);
            sel.setWhere(w);
            @SuppressWarnings("unchecked")
            final List<String> names = (List<String>) execute(sel.asString(), SQLDataSource.COLUMN_LIST_HANDLER);
            return names.toArray(new String[names.size()]);
        } catch (Exception e) {
            throw new BackingStoreException(e);
        }
    }

    @Override
    protected SQLPreferences childSpi(String name) {
        return new SQLPreferences(this, name, this.prefT.getDBRoot());
    }

    @Override
    protected void syncSpi() throws BackingStoreException {
        this.flushSpi();
        this.values = null;
    }

    @Override
    protected void flushSpi() throws BackingStoreException {
        if (this.removedKeys.size() > 0 || this.changedValues.size() > 0) {
            try {
                // also delete changed, so we can insert afterwards
                this.deleteValues(CollectionUtils.union(this.removedKeys, this.changedValues.keySet()));
                this.removedKeys.clear();

                if (this.changedValues.size() > 0) {
                    final int nodeID = getNode(true).getID();
                    final List<String> insValues = new ArrayList<String>(this.changedValues.size());
                    for (final Entry<String, String> e : this.changedValues.entrySet()) {
                        insValues.add("(" + nodeID + ", " + this.prefT.getBase().quoteString(e.getKey()) + ", " + this.prefT.getBase().quoteString(e.getValue()) + ")");
                    }

                    SQLRowValues.insertCount(this.prefT, "(\"ID_NODE\", \"NAME\", \"VALUE\") VALUES" + CollectionUtils.join(insValues, ", "));
                    this.changedValues.clear();
                }
            } catch (Exception e) {
                throw new BackingStoreException(e);
            }
        }
    }
}

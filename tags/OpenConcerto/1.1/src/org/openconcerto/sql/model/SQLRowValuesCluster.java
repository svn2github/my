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
 
 package org.openconcerto.sql.model;

import org.openconcerto.sql.model.SQLRowValues.ForeignCopyMode;
import org.openconcerto.sql.model.SQLRowValues.ReferentChangeEvent;
import org.openconcerto.sql.model.SQLRowValues.ReferentChangeListener;
import org.openconcerto.sql.model.graph.Path;
import org.openconcerto.sql.utils.SQLUtils;
import org.openconcerto.utils.CollectionMap;
import org.openconcerto.utils.CollectionUtils;
import org.openconcerto.utils.CompareUtils;
import org.openconcerto.utils.Matrix;
import org.openconcerto.utils.RecursionType;
import org.openconcerto.utils.cc.Closure;
import org.openconcerto.utils.cc.IClosure;
import org.openconcerto.utils.cc.ITransformer;
import org.openconcerto.utils.cc.IdentityHashSet;
import org.openconcerto.utils.cc.IdentitySet;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EventObject;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A set of linked SQLRowValues.
 * 
 * @author Sylvain
 */
public class SQLRowValuesCluster {
    private static final Comparator<SQLField> FIELD_COMPARATOR = new Comparator<SQLField>() {
        @Override
        public int compare(SQLField o1, SQLField o2) {
            return o1.getSQLName().quote().compareTo(o2.getSQLName().quote());
        }
    };

    /**
     * The list of links in the order they've been set. This allows deterministic and predictable
     * insertion order. E.g. for
     * 
     * <pre>
     * r2.put(f, r1);
     * r3.put(f2, r2);
     * r4.put(f2, r2);
     * </pre>
     * 
     * the links will be :
     * 
     * <pre>
     * r1
     * r2 f r1
     * r3 f2 r2
     * r4 f2 r2
     * </pre>
     */
    private final List<Link> links;
    private final IdentitySet<SQLRowValues> items;
    // only used in store(), MAYBE returned from store() and remove getRow()
    private final Map<SQLRowValues, Node> nodes;
    // { vals -> listener on vals' graph }
    private Map<SQLRowValues, List<ValueChangeListener>> listeners;

    private SQLRowValuesCluster() {
        this.links = new ArrayList<Link>();
        // SQLRowValues equals() depends on their values, but we must tell apart each reference
        this.items = new IdentityHashSet<SQLRowValues>();
        this.nodes = new IdentityHashMap<SQLRowValues, Node>();
        this.listeners = null;
    }

    SQLRowValuesCluster(SQLRowValues vals) {
        this();
        this.links.add(new Link(vals));
        this.items.add(vals);
    }

    private final SQLRowValues getHead() {
        return this.links.get(0).getSrc();
    }

    private final Map<SQLRowValues, Node> getNodes() {
        return this.nodes;
    }

    private final DBSystemRoot getSystemRoot() {
        return this.getHead().getTable().getDBSystemRoot();
    }

    /**
     * All the rowValues in this cluster.
     * 
     * @return the set of SQLRowValues.
     */
    public final Set<SQLRowValues> getItems() {
        return Collections.unmodifiableSet(this.items);
    }

    public final int size() {
        return this.items.size();
    }

    public final boolean contains(SQLRowValues start) {
        return this.items.contains(start);
    }

    private final void containsCheck(SQLRowValues vals) {
        if (!this.contains(vals))
            throw new IllegalArgumentException(vals + " not in " + this);
    }

    void remove(SQLRowValues src, SQLField f, SQLRowValues dest) {
        assert dest != null;
        assert src.getGraph() == this;
        assert src.getTable() == f.getTable();

        final Link toRm = new Link(src, f, dest);
        this.links.remove(toRm);

        // test if the removal of the link split the graph
        final IdentitySet<SQLRowValues> reachable = this.getReachable(src);
        if (reachable.size() < this.size()) {
            final SQLRowValuesCluster newCluster = new SQLRowValuesCluster();

            // moves the links no longer in us into a new cluster
            final Iterator<Link> iter = this.links.iterator();
            while (iter.hasNext()) {
                final Link l = iter.next();
                // the graph is split in two, so every link is in one part
                assert l.getDest() == null || reachable.contains(l.getSrc()) == reachable.contains(l.getDest());
                // hence only test the source
                if (!reachable.contains(l.getSrc())) {
                    iter.remove();
                    newCluster.links.add(l);
                }
            }
            // move unreachable items
            assert newCluster.items.isEmpty();
            final Iterator<SQLRowValues> itemIter = this.items.iterator();
            while (itemIter.hasNext()) {
                final SQLRowValues key = itemIter.next();
                if (!reachable.contains(key)) {
                    itemIter.remove();
                    newCluster.items.add(key);
                    if (this.listeners != null && this.listeners.containsKey(key))
                        newCluster.getListeners().put(key, this.listeners.remove(key));
                }
            }
            assert !newCluster.items.isEmpty() && !CollectionUtils.containsAny(this.items, newCluster.items);
            this.nodes.keySet().retainAll(reachable);

            for (final SQLRowValues vals : newCluster.getItems())
                vals.setGraph(newCluster);
        }
    }

    void add(SQLRowValues src, SQLField f, SQLRowValues dest) {
        assert dest != null;
        assert src.getGraph() == this;
        assert this.contains(src);
        assert src.getTable() == f.getTable();

        final Link toAdd = new Link(src, f, dest);
        if (this.contains(dest)) {
            // both source and dest are in us
            this.links.add(toAdd);
        } else {
            assert src.getGraph() != dest.getGraph();
            final SQLRowValuesCluster destGraph = dest.getGraph();
            // merge the two graphs
            // add dest before since it will be needed to store us
            this.links.add(0, toAdd);
            this.links.addAll(0, destGraph.links);
            destGraph.links.clear();
            this.items.addAll(destGraph.getItems());
            for (final SQLRowValues newlyAdded : destGraph.getItems()) {
                newlyAdded.setGraph(this);
            }
            destGraph.items.clear();
            if (destGraph.listeners != null) {
                this.getListeners().putAll(destGraph.listeners);
                destGraph.listeners = null;
            }
        }
        assert src.getGraph() == dest.getGraph();
    }

    public final SQLRow getRow(SQLRowValues vals) {
        this.containsCheck(vals);
        return this.nodes.get(vals).getStoredRow();
    }

    private IdentitySet<SQLRowValues> getReachable(final SQLRowValues from) {
        final IdentitySet<SQLRowValues> res = new IdentityHashSet<SQLRowValues>();
        getReachableRec(from, res);
        return res;
    }

    private void getReachableRec(final SQLRowValues from, final IdentitySet<SQLRowValues> acc) {
        if (!acc.add(from))
            return;

        for (final SQLRowValues fVals : from.getForeigns().values()) {
            this.getReachableRec(fVals, acc);
        }
        for (final SQLRowValues fVals : from.getReferentRows()) {
            this.getReachableRec(fVals, acc);
        }
    }

    synchronized final SQLRowValues deepCopy(SQLRowValues v) {
        // copy all rowValues of this graph
        final Map<SQLRowValues, SQLRowValues> noLinkCopy = new IdentityHashMap<SQLRowValues, SQLRowValues>();
        for (final SQLRowValues n : this.getItems())
            noLinkCopy.put(n, new SQLRowValues(n, ForeignCopyMode.NO_COPY));

        // and link them together
        for (final SQLRowValues n : this.getItems()) {
            for (final Entry<String, SQLRowValues> e : n.getForeigns().entrySet())
                noLinkCopy.get(n).put(e.getKey(), noLinkCopy.get(e.getValue()));
        }

        return noLinkCopy.get(v);
    }

    public synchronized final void store(final StoreMode mode) throws SQLException {
        this.reset();
        // check validity first, avoid beginning a transaction for nothing
        // do it after reset otherwise check previous values
        for (final Node n : this.getNodes().values()) {
            n.noLink.checkValidity();
        }
        // this will hold the links and their ID as they are known
        /**
         * A cycle example :
         * 
         * <pre>
         * s null
         * c ID_SITE s
         * c null
         * s ID_CONTACT_RAPPORT c
         * s ID_CONTACT_UTILE c
         * </pre>
         * 
         * First s will be inserted :
         * 
         * <pre>
         * c ID_SITE sID
         * c null
         * s ID_CONTACT_RAPPORT c
         * s ID_CONTACT_UTILE c
         * </pre>
         * 
         * Then c :
         * 
         * <pre>
         * s ID_CONTACT_RAPPORT cID
         * s ID_CONTACT_UTILE cID
         * </pre>
         * 
         * And finally, s will be updated.
         */
        final List<StoringLink> storingLinks = new ArrayList<StoringLink>(this.links.size());
        for (final Link l : this.links)
            storingLinks.add(new StoringLink(l));

        // store the whole graph atomically
        final List<SQLTableEvent> events = SQLUtils.executeAtomic(getSystemRoot().getDataSource(), new ConnectionHandlerNoSetup<List<SQLTableEvent>, SQLException>() {
            @Override
            public List<SQLTableEvent> handle(SQLDataSource ds) throws SQLException {
                final List<SQLTableEvent> res = new ArrayList<SQLTableEvent>();
                while (storingLinks.size() > 0) {
                    final StoringLink toStore = storingLinks.remove(0);
                    if (!toStore.canStore())
                        throw new IllegalStateException();
                    final Node n = getNodes().get(toStore.getSrc());

                    // merge the maximum of links starting from the row to be stored
                    boolean lastDBAccess = true;
                    final Iterator<StoringLink> iter = storingLinks.iterator();
                    while (iter.hasNext()) {
                        final StoringLink sl = iter.next();
                        if (sl.getSrc() == toStore.getSrc()) {
                            if (sl.canStore()) {
                                iter.remove();
                                if (sl.destID != null)
                                    n.noLink.put(sl.getField().getName(), sl.destID);
                            } else {
                                lastDBAccess = false;
                            }
                        }
                    }

                    if (n.isStored()) {
                        // if there's a cycle, we have to update an already inserted row
                        res.add(n.update());
                    } else {
                        res.add(n.store(mode));
                        final SQLRow r = n.getStoredRow();

                        // fill the noLink of referent nodes with the new ID
                        for (final StoringLink sl : storingLinks) {
                            if (sl.getDest() == toStore.getSrc()) {
                                sl.destID = r.getIDNumber();
                                getNodes().get(sl.getSrc()).noLink.put(sl.getField().getName(), r.getIDNumber());
                            }
                        }
                    }

                    // link together the new values
                    // if there is a cycle not all foreign keys can be stored at the same time, so
                    // wait for the last DB access
                    if (lastDBAccess)
                        for (final Map.Entry<String, SQLRowValues> e : toStore.getSrc().getForeigns().entrySet()) {
                            final SQLRowValues foreign = getNodes().get(e.getValue()).getStoredValues();
                            assert foreign != null : "since this the last db access for this row, all foreigns should have been inserted";
                            // check coherence
                            if (n.getStoredValues().getLong(e.getKey()) != foreign.getIDNumber().longValue())
                                throw new IllegalStateException("stored " + n.getStoredValues().getObject(e.getKey()) + " but foreign is " + SQLRowValues.trim(foreign));
                            n.getStoredValues().put(e.getKey(), foreign);
                        }
                }
                return res;
            }
        });
        // fire events
        for (final SQLTableEvent n : events) {
            // MAYBE put a Map<SQLRowValues, SQLTableEvent> to know how our fellow values have been
            // affected
            n.getTable().fire(n);
        }
    }

    private final void reset() {
        this.getNodes().clear();
        for (final SQLRowValues vals : this.getItems()) {
            this.getNodes().put(vals, new Node(vals));
        }
    }

    /**
     * Walk the graph from the passed node, executing the closure for each node on the path. NOTE
     * that this method only goes one way through foreign keys, ie if this cluster is a tree and
     * <code>start</code> is not the root, some nodes will not be traversed.
     * 
     * @param <T> type of acc
     * @param start where to start the walk.
     * @param acc the initial value.
     * @param closure what to do on each node.
     */
    public final <T> void walk(final SQLRowValues start, T acc, ITransformer<State<T>, T> closure) {
        this.walk(start, acc, closure, RecursionType.BREADTH_FIRST);
    }

    /**
     * Walk the graph from the passed node, executing the closure for each node on the path. NOTE
     * that this method only goes one way through foreign keys, ie if this cluster is a tree and
     * <code>start</code> is not the root, some nodes will not be traversed. Also you can stop the
     * recursion by throwing {@link StopRecurseException} in <code>closure</code>.
     * 
     * @param <T> type of acc
     * @param start where to start the walk.
     * @param acc the initial value.
     * @param closure what to do on each node.
     * @param recType how to recurse.
     * @return the exception that stopped the recursion, <code>null</code> if none was thrown.
     */
    public final <T> StopRecurseException walk(final SQLRowValues start, T acc, ITransformer<State<T>, T> closure, RecursionType recType) {
        return this.walk(start, acc, closure, recType, true);
    }

    public final <T> StopRecurseException walk(final SQLRowValues start, T acc, ITransformer<State<T>, T> closure, RecursionType recType, final Boolean foreign) {
        return this.walk(start, acc, closure, recType, foreign, true);
    }

    final <T> StopRecurseException walk(final SQLRowValues start, T acc, ITransformer<State<T>, T> closure, RecursionType recType, final Boolean foreign, final boolean includeStart) {
        this.containsCheck(start);
        return this.walk(new State<T>(Collections.singletonList(start), new Path(start.getTable()), acc, closure), recType, foreign, includeStart);
    }

    /**
     * Walk through the graph from the passed state.
     * 
     * @param <T> type of acc.
     * @param state the current position in the graph.
     * @param recType how to recurse.
     * @param foreign <code>true</code> to follow foreign keys, <code>false</code> to go the other
     *        way, <code>null</code> for both.
     * @param computeThisState <code>false</code> if the <code>state</code> should not be
     *        {@link State#compute() computed}.
     * @return the exception that stopped the recursion, <code>null</code> if none was thrown.
     */
    private final <T> StopRecurseException walk(final State<T> state, RecursionType recType, final Boolean foreign, final boolean computeThisState) {
        if (computeThisState && recType == RecursionType.BREADTH_FIRST) {
            final StopRecurseException e = state.compute();
            if (e != null)
                return e;
        }
        // get the foreign or referents rowValues
        StopRecurseException res = null;
        if (foreign == null || Boolean.TRUE.equals(foreign)) {
            res = rec(state, recType, foreign, true);
        }
        if (res != null)
            return res;
        if (foreign == null || Boolean.FALSE.equals(foreign)) {
            res = rec(state, recType, foreign, false);
        }
        if (res != null)
            return res;

        if (computeThisState && recType == RecursionType.DEPTH_FIRST) {
            final StopRecurseException e = state.compute();
            if (e != null)
                return e;
        }
        return null;
    }

    private <T> StopRecurseException rec(final State<T> state, RecursionType recType, final Boolean foreign, final boolean actualForeign) {
        final SQLRowValues current = state.getCurrent();
        final List<SQLRowValues> currentValsPath = state.getValsPath();
        final CollectionMap<SQLField, SQLRowValues> nextVals;
        if (actualForeign) {
            final Map<SQLField, SQLRowValues> foreigns = current.getForeignsBySQLField();
            nextVals = new CollectionMap<SQLField, SQLRowValues>(new ArrayList<SQLRowValues>(), foreigns.size());
            nextVals.putAll(foreigns);
        } else
            nextVals = current.getReferents();
        // predictable and repeatable order
        final List<SQLField> keys = new ArrayList<SQLField>(nextVals.keySet());
        Collections.sort(keys, FIELD_COMPARATOR);
        for (final SQLField f : keys) {
            for (final SQLRowValues v : nextVals.getNonNull(f)) {
                // avoid infinite loop (don't use equals so that we can go over several equals rows)
                if (!state.identityContains(v)) {
                    final Path path = new Path(state.getPath());
                    path.add(f, actualForeign);
                    final List<SQLRowValues> valsPath = new ArrayList<SQLRowValues>(currentValsPath);
                    valsPath.add(v);
                    final StopRecurseException e = this.walk(new State<T>(valsPath, path, state.getAcc(), state.closure), recType, foreign, true);
                    if (e != null && e.isCompletely())
                        return e;
                }
            }
        }
        return null;
    }

    final void walkFields(final SQLRowValues start, IClosure<FieldPath> closure, final boolean includeFK) {
        walkFields(start, new Path(start.getTable()), Collections.singletonList(start), closure, includeFK);
    }

    private void walkFields(final SQLRowValues current, final Path p, final List<SQLRowValues> currentValsPath, IClosure<FieldPath> closure, final boolean includeFK) {
        final Map<String, SQLRowValues> foreigns = current.getForeigns();
        for (final String field : current.getFields()) {
            final boolean isFK = foreigns.containsKey(field);
            if (!isFK || includeFK)
                closure.executeChecked(new FieldPath(p, field));
            if (isFK) {
                final SQLRowValues newVals = foreigns.get(field);
                // avoid infinite loop
                if (!currentValsPath.contains(newVals)) {
                    final Path newP = new Path(p);
                    newP.add(current.getTable().getField(field), true);
                    final List<SQLRowValues> newValsPath = new ArrayList<SQLRowValues>(currentValsPath);
                    newValsPath.add(newVals);
                    this.walkFields(newVals, newP, newValsPath, closure, includeFK);
                }
            }
        }
    }

    /**
     * Copy this leaving only the fields specified by <code>graph</code>.
     * 
     * @param start where to start pruning, eg RECEPTEUR {DESIGNATION="rec", CONSTAT="ok",
     *        ID_LOCAL=LOCAL{DESIGNATION="local"}}.
     * @param graph the structure wanted, eg RECEPTEUR {DESIGNATION=null}.
     * @return a copy of graph with our values, eg RECEPTEUR {DESIGNATION="rec"}.
     */
    public final SQLRowValues prune(final SQLRowValues start, SQLRowValues graph) {
        this.containsCheck(start);
        if (!start.getTable().equals(graph.getTable()))
            throw new IllegalArgumentException(start + " is not from the same table as " + graph);
        final SQLRowValues res = new SQLRowValues(start.getTable());
        graph.walkGraph(res, new ITransformer<State<SQLRowValues>, SQLRowValues>() {
            @Override
            public SQLRowValues transformChecked(State<SQLRowValues> input) {
                final SQLRowValues current = input.getCurrent();
                final SQLRowValues creatingVals;
                if (input.getPath().length() == 0) {
                    creatingVals = input.getAcc();
                } else {
                    creatingVals = new SQLRowValues(current.getTable());
                    input.getAcc().put(input.getFrom().getName(), creatingVals);
                }
                final SQLRowValues source = start.assurePath(input.getPath());
                // flatten to not load rowValues
                creatingVals.load(new SQLRowValues(source).flatten(true), current.getFields());
                return creatingVals;
            }
        });
        return res;
    }

    final void grow(final SQLRowValues start, final SQLRowValues toGrow, final boolean checkFields) {
        this.containsCheck(start);
        if (!start.getTable().equals(toGrow.getTable()))
            throw new IllegalArgumentException(start + " is not from the same table as " + toGrow);
        this.walk(start, null, new ITransformer<State<Object>, Object>() {
            @Override
            public Object transformChecked(State<Object> input) {
                final SQLRowValues existing = toGrow.followPath(input.getPath());
                if (existing == null || (checkFields && !existing.getFields().containsAll(input.getCurrent().getFields()))) {
                    final SQLRowValues leaf = toGrow.assurePath(input.getPath());
                    if (leaf.hasID()) {
                        final SQLRowValuesListFetcher fetcher = new SQLRowValuesListFetcher(input.getCurrent());
                        fetcher.setSelID(leaf.getID());
                        fetcher.setSelTransf(new ITransformer<SQLSelect, SQLSelect>() {
                            @Override
                            public SQLSelect transformChecked(SQLSelect input) {
                                // don't exclude undef otherwise cannot grow eg
                                // LOCAL.ID_FAMILLE_2 = 1
                                input.setExcludeUndefined(false);
                                return input;
                            }
                        });
                        final SQLRowValues fetched = CollectionUtils.getSole(fetcher.fetch());
                        if (fetched == null)
                            throw new IllegalArgumentException("no row for " + fetcher);
                        leaf.load(fetched, null);
                    } else
                        throw new IllegalArgumentException("cannot expand, missing ID in " + leaf + " at " + input.getPath());
                }
                return null;
            }
        }, RecursionType.BREADTH_FIRST);
    }

    public final String contains(final SQLRowValues start, SQLRowValues graph) {
        return this.contains(start, graph, true);
    }

    /**
     * Whether the tree begining at <code>start</code> contains the tree begining at
     * <code>graph</code>. Ie each path of <code>graph</code> must exist from <code>start</code>.
     * 
     * @param start a SQLRowValues of this.
     * @param graph another SQLRowValues.
     * @param checkFields <code>true</code> to check that each rowValues of this containsAll the
     *        fields of the other.
     * @return a String explaining the first problem, <code>null</code> if <code>graph</code> is
     *         contained in this.
     */
    public final String contains(final SQLRowValues start, SQLRowValues graph, final boolean checkFields) {
        this.containsCheck(start);
        if (!start.getTable().equals(graph.getTable()))
            throw new IllegalArgumentException(start + " is not from the same table as " + graph);
        final StopRecurseException res = graph.getGraph().walk(graph, null, new ITransformer<State<Object>, Object>() {
            @Override
            public Object transformChecked(State<Object> input) {
                final SQLRowValues v = start.followPath(input.getPath());
                if (v == null)
                    throw new StopRecurseException("no " + input.getPath() + " in " + start);
                if (checkFields && !v.getFields().containsAll(input.getCurrent().getFields()))
                    throw new StopRecurseException("at " + input.getPath() + " " + v.getFields() + " does not contain " + input.getCurrent().getFields());

                return null;
            }
        }, RecursionType.BREADTH_FIRST);
        return res == null ? null : res.getMessage();
    }

    /**
     * Return a graphical representation of the tree rooted at <code>root</code>. The returned
     * string is akin to the result of a query :
     * 
     * <pre>
     * BATIMENT[2]      LOCAL[5]         CPI_BT[5]        
     *                  LOCAL[3]         
     *                  LOCAL[2]         CPI_BT[3]        
     *                                   CPI_BT[2]
     * </pre>
     * 
     * In the above example, the BATIMENT has 3 LOCAL. LOCAL[3] is empty and LOCAL[2] has 2 CPI.
     * 
     * @param root the root of the tree to print, eg BATIMENT[2].
     * @param cellLength the length of each cell.
     * @return a string representing the tree.
     */
    public final String printTree(final SQLRowValues root, int cellLength) {
        this.containsCheck(root);
        final Map<SQLRowValues, Integer> ys = new IdentityHashMap<SQLRowValues, Integer>();
        final AtomicInteger currentY = new AtomicInteger(0);
        final Matrix<SQLRowValues> matrix = new Matrix<SQLRowValues>();
        this.walk(root, null, new Closure<State<Object>>() {
            @Override
            public void executeChecked(State<Object> input) {
                // x is easy : it's the length of the path
                // y : for each rowValues we go up the tree and set the y (if not already set)
                // then y is either this value or the current line.
                final SQLRowValues r = input.getCurrent();
                final int y;
                if (ys.containsKey(r))
                    y = ys.get(r);
                else
                    y = currentY.getAndIncrement();
                matrix.put(input.getPath().length(), y, input.getCurrent());

                final SQLRowValues ancestor = input.getPrevious();
                if (ancestor != null) {
                    ancestor.walkGraph(null, new Closure<State<Object>>() {
                        @Override
                        public void executeChecked(State<Object> input) {
                            final SQLRowValues ancestorRow = input.getCurrent();
                            if (!ys.containsKey(ancestorRow))
                                ys.put(ancestorRow, y);
                            else
                                throw new StopRecurseException();
                        }
                    });
                }
            }
        }, RecursionType.DEPTH_FIRST, false);

        return matrix.print(cellLength, new ITransformer<SQLRowValues, String>() {
            @Override
            public String transformChecked(SQLRowValues input) {
                if (input == null)
                    return "";
                else if (input.hasID())
                    // avoid requests
                    return input.asRow().simpleToString();
                else
                    return input.getTable().toString();
            }
        });
    }

    public final String printNodes() {
        final StringBuilder sb = new StringBuilder(this.getClass().getSimpleName() + " of " + size() + " nodes:\n");
        for (final SQLRowValues n : getItems()) {
            sb.append(System.identityHashCode(n));
            sb.append(" ");
            sb.append(n.getTable());
            sb.append("\t");
            for (final Map.Entry<String, SQLRowValues> e : n.getForeigns().entrySet()) {
                sb.append(e.getKey());
                sb.append(" -> ");
                sb.append(System.identityHashCode(e.getValue()));
                sb.append(" ; ");
            }
            sb.append(new SQLRowValues(n, ForeignCopyMode.NO_COPY));
            sb.append("\n");
        }
        return sb.toString();
    }

    final boolean equals(final SQLRowValues vals, final SQLRowValues other) {
        this.containsCheck(vals);
        if (this == other.getGraph())
            return true;
        // don't call walk() if we can avoid as it is quite costly
        if (this.size() != other.getGraph().size() || !vals.equalsJustThis(other))
            return false;
        if (this.size() == 1)
            return true;
        // BREADTH_FIRST no need to go deep if the first values are not equals
        final List<SQLRowValues> flatList = new ArrayList<SQLRowValues>();
        this.walk(vals, flatList, new ITransformer<State<List<SQLRowValues>>, List<SQLRowValues>>() {
            @Override
            public List<SQLRowValues> transformChecked(State<List<SQLRowValues>> input) {
                input.getAcc().add(input.getCurrent());
                return input.getAcc();
            }
        }, RecursionType.BREADTH_FIRST, null);
        assert flatList.size() == this.size() : "missing rows";
        // now walk the other graph, checking that each row is equal
        // (this works because walk() always goes with the same order, see #FIELD_COMPARATOR)
        final AtomicInteger index = new AtomicInteger(0);
        final StopRecurseException stop = other.getGraph().walk(other, null, new ITransformer<State<Object>, Object>() {
            @Override
            public Object transformChecked(State<Object> input) {
                if (!input.getCurrent().equalsJustThis(flatList.get(index.getAndIncrement())))
                    throw new StopRecurseException("unequal at " + input.getPath());
                return input.getAcc();
            }
        }, RecursionType.BREADTH_FIRST, null);
        return stop == null;
    }

    static public final class StopRecurseException extends RuntimeException {

        private boolean completely = true;

        public StopRecurseException() {
            super();
        }

        public StopRecurseException(String message, Throwable cause) {
            super(message, cause);
        }

        public StopRecurseException(String message) {
            super(message);
        }

        public StopRecurseException(Throwable cause) {
            super(cause);
        }

        public final StopRecurseException setCompletely(boolean completely) {
            this.completely = completely;
            return this;
        }

        public final boolean isCompletely() {
            return this.completely;
        }
    }

    static public final class State<T> {
        private final List<SQLRowValues> valsPath;
        private final Path path;
        private T acc;
        private final ITransformer<State<T>, T> closure;

        State(List<SQLRowValues> valsPath, Path path, T acc, ITransformer<State<T>, T> closure) {
            super();
            this.valsPath = valsPath;
            this.path = path;
            this.acc = acc;
            this.closure = closure;
        }

        public SQLField getFrom() {
            return this.path.length() == 0 ? null : this.path.getSingleStep(this.path.length() - 1);
        }

        /**
         * Whether the last step of the path was taken backwards through a foreign field. Eg
         * <code>true</code> if the path is BATIMENT,LOCAL.ID_BATIMENT, and <code>false</code> if
         * LOCAL,LOCAL.ID_BATIMENT.
         * 
         * @return <code>true</code> if the last step was backwards.
         */
        public final boolean isBackwards() {
            if (this.path.length() == 0)
                throw new IllegalStateException("empty path");
            return this.path.isBackwards(this.path.length() - 1);
        }

        /**
         * Compute the new acc.
         * 
         * @return whether this recursion should continue.
         */
        StopRecurseException compute() {
            try {
                this.acc = this.closure.transformChecked(this);
                return null;
            } catch (StopRecurseException e) {
                return e;
            }
        }

        @Override
        public String toString() {
            return this.getClass().getSimpleName() + " path: " + this.path + " current node: " + this.getCurrent() + " current acc: " + this.getAcc();
        }

        public final SQLRowValues getCurrent() {
            return CollectionUtils.getLast(this.valsPath);
        }

        public final SQLRowValues getPrevious() {
            return CollectionUtils.getNoExn(this.valsPath, this.valsPath.size() - 2);
        }

        public final List<SQLRowValues> getValsPath() {
            return this.valsPath;
        }

        final boolean identityContains(final SQLRowValues vals) {
            for (final SQLRowValues v : this.valsPath)
                if (vals == v)
                    return true;
            return false;
        }

        public Path getPath() {
            return this.path;
        }

        public T getAcc() {
            return this.acc;
        }
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + " " + this.links;
    }

    private static class Link {
        private final SQLRowValues src;
        private final SQLField f;
        private final SQLRowValues dest;

        public Link(final SQLRowValues src) {
            this(src, null, null);
        }

        public Link(final SQLRowValues src, final SQLField f, final SQLRowValues dest) {
            if (src == null)
                throw new NullPointerException("src is null");
            this.src = src;
            this.f = f;
            this.dest = dest;
        }

        public final SQLRowValues getSrc() {
            return this.src;
        }

        public final SQLRowValues getDest() {
            return this.dest;
        }

        public final SQLField getField() {
            return this.f;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + this.src.hashCode();
            result = prime * result + ((this.dest == null) ? 0 : this.dest.hashCode());
            result = prime * result + ((this.f == null) ? 0 : this.f.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;

            final Link other = (Link) obj;
            return this.src == other.src && this.dest == other.dest && CompareUtils.equals(this.f, other.f);
        }

        @Override
        public String toString() {
            return this.getClass().getSimpleName() + " " + System.identityHashCode(this.src) + (this.f == null ? "" : " " + this.f.getName() + " " + System.identityHashCode(this.dest));
        }
    }

    private static final class StoringLink extends Link {

        private Number destID;

        private StoringLink(Link l) {
            super(l.getSrc(), l.getField(), l.getDest());
            this.destID = null;
        }

        public final boolean canStore() {
            return this.getDest() == null || this.destID != null;
        }

        @Override
        public String toString() {
            return super.toString() + " destID: " + this.destID;
        }
    }

    private static final class Node {

        // don't use noLink since it might contains foreigns if store() was just called
        // or it might be out of sync with vals since the graph is only recreated on foreign change
        /** vals without any links */
        private SQLRowValues noLink;
        private final List<SQLTableEvent> modif;

        public Node(final SQLRowValues vals) {
            this.modif = new ArrayList<SQLTableEvent>();
            this.noLink = new SQLRowValues(vals, ForeignCopyMode.NO_COPY);
        }

        public SQLTableEvent store(StoreMode mode) throws SQLException {
            assert !this.isStored();
            return this.addEvent(mode.execOn(this.noLink));
        }

        public SQLTableEvent update() throws SQLException {
            assert this.isStored();

            // fields that have been updated since last store
            final Set<String> fieldsToUpdate = new HashSet<String>(this.noLink.getFields());
            fieldsToUpdate.removeAll(this.getEvent().getFieldNames());
            assert fieldsToUpdate.size() > 0;

            final SQLRowValues updatingVals = this.getStoredRow().createEmptyUpdateRow();
            updatingVals.load(this.noLink, fieldsToUpdate);

            final SQLTableEvent evt = new Node(updatingVals).store(StoreMode.COMMIT);
            // Update previous rowValues, and use it for the new event
            // that way there's only one graph of rowValues (with the final values) for all events.
            // Load all fields since updating 1 field might change the value of another (e.g.
            // with a trigger).
            this.getStoredValues().load(evt.getRow(), null);
            evt.setRowValues(this.getStoredValues());
            return this.addEvent(evt);
        }

        public final boolean isStored() {
            return this.modif.size() > 0;
        }

        // the last stored row, can be null for non-rowable tables
        public final SQLRow getStoredRow() {
            return this.getEvent() == null ? null : this.getEvent().getRow();
        }

        public final SQLRowValues getStoredValues() {
            // all events have the same values
            return this.getEvent() == null ? null : this.getEvent().getRowValues();
        }

        // the last event
        private final SQLTableEvent getEvent() {
            return CollectionUtils.getLast(this.modif);
        }

        private final SQLTableEvent addEvent(SQLTableEvent evt) {
            assert evt != null;
            this.modif.add(evt);
            return evt;
        }

        @Override
        public String toString() {
            return this.getClass().getSimpleName() + " " + this.noLink;
        }
    }

    /**
     * What to do on each rowVals.
     * 
     * @author Sylvain
     */
    public static abstract class StoreMode {
        abstract SQLTableEvent execOn(SQLRowValues vals) throws SQLException;

        public static final StoreMode COMMIT = new Commit();
    }

    public static class Insert extends StoreMode {

        private final boolean insertPK;
        private final boolean insertOrder;

        public Insert(boolean insertPK, boolean insertOrder) {
            super();
            this.insertPK = insertPK;
            this.insertOrder = insertOrder;
        }

        @Override
        SQLTableEvent execOn(SQLRowValues vals) throws SQLException {
            final Set<SQLField> autoFields = new HashSet<SQLField>();
            if (!this.insertPK)
                autoFields.addAll(vals.getTable().getPrimaryKeys());
            if (!this.insertOrder)
                autoFields.add(vals.getTable().getOrderField());
            return vals.insertJustThis(autoFields);
        }
    }

    public static class Commit extends StoreMode {
        @Override
        SQLTableEvent execOn(SQLRowValues vals) throws SQLException {
            return vals.commitJustThis();
        }
    }

    // * listeners

    // create if necessary
    private final Map<SQLRowValues, List<ValueChangeListener>> getListeners() {
        if (this.listeners == null)
            this.listeners = new IdentityHashMap<SQLRowValues, List<ValueChangeListener>>(4);
        return this.listeners;
    }

    final void addValueListener(final SQLRowValues vals, ValueChangeListener l) {
        this.containsCheck(vals);
        List<ValueChangeListener> list = this.getListeners().get(vals);
        if (list == null) {
            list = new ArrayList<ValueChangeListener>();
            this.getListeners().put(vals, list);
        }
        list.add(l);
    }

    final void removeValueListener(final SQLRowValues vals, ValueChangeListener l) {
        if (this.listeners != null && this.listeners.containsKey(vals)) {
            final List<ValueChangeListener> list = this.listeners.get(vals);
            list.remove(l);
            // never leave an empty list so that hasListeners() works
            if (list.size() == 0)
                this.listeners.remove(vals);
        }
    }

    final void fireModification(SQLRowValues vals, String fieldName, Object newValue) {
        if (hasListeners())
            fireModification(new ValueChangeEvent(vals, fieldName, newValue));
    }

    final void fireModification(SQLRowValues vals, Map<String, ?> m) {
        if (hasListeners())
            fireModification(new ValueChangeEvent(vals, m));
    }

    final void fireModification(SQLRowValues vals, Set<String> removed) {
        if (hasListeners())
            fireModification(new ValueChangeEvent(vals, removed));
    }

    private final void fireModification(final ValueChangeEvent evt) {
        for (final List<ValueChangeListener> list : this.listeners.values())
            for (ValueChangeListener l : list)
                l.valueChange(evt);
    }

    final void fireModification(final ReferentChangeEvent evt) {
        if (referentFireNeeded(evt.isAddition())) {
            for (final List<ValueChangeListener> list : this.listeners.values())
                for (ValueChangeListener l : list)
                    l.referentChange(evt);
        }
    }

    final boolean referentFireNeeded(final boolean put) {
        // if isAddition there will be a valueChange() for the same put()
        return this.hasListeners() && !put;
    }

    final boolean hasListeners() {
        return this.listeners != null && this.listeners.size() > 0;
    }

    public static class ValueChangeEvent extends EventObject {

        private final Map<String, ?> added;
        private final Set<String> removed;

        private ValueChangeEvent(final SQLRowValues vals, final Map<String, ?> m) {
            super(vals);
            this.added = Collections.unmodifiableMap(m);
            this.removed = Collections.emptySet();
        }

        public ValueChangeEvent(SQLRowValues vals, String fieldName, Object newValue) {
            super(vals);
            this.added = Collections.singletonMap(fieldName, newValue);
            this.removed = Collections.emptySet();
        }

        public ValueChangeEvent(SQLRowValues vals, Set<String> removed) {
            super(vals);
            this.added = Collections.emptyMap();
            this.removed = Collections.unmodifiableSet(removed);
        }

        @Override
        public SQLRowValues getSource() {
            return (SQLRowValues) super.getSource();
        }

        public final Set<String> getAddedFields() {
            return this.added.keySet();
        }

        public final Set<String> getRemovedFields() {
            return this.removed;
        }

        public final Map<String, ?> getAddedValues() {
            return this.added;
        }

        @Override
        public String toString() {
            return super.toString() + " added : " + getAddedFields() + " removed: " + getRemovedFields();
        }
    }

    /**
     * This listener is notified whenever a rowValues changes. Note that
     * {@link ValueChangeListener#referentChange(ReferentChangeEvent)} is only called for
     * {@link ReferentChangeEvent#isRemoval() removals} since for addition there will be a
     * {@link ValueChangeListener#valueChange(ValueChangeEvent)}.
     * 
     * @author Sylvain CUAZ
     */
    public static interface ValueChangeListener extends ReferentChangeListener {

        void valueChange(ValueChangeEvent evt);

    }
}

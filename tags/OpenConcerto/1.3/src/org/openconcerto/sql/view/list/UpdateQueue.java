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
 
 package org.openconcerto.sql.view.list;

import org.openconcerto.sql.model.SQLRow;
import org.openconcerto.sql.model.SQLRowValues;
import org.openconcerto.sql.model.SQLRowValuesCluster.State;
import org.openconcerto.sql.model.SQLTable;
import org.openconcerto.sql.model.SQLTableEvent;
import org.openconcerto.sql.model.SQLTableEvent.Mode;
import org.openconcerto.sql.model.graph.Link.Direction;
import org.openconcerto.sql.model.SQLTableModifiedListener;
import org.openconcerto.sql.view.list.UpdateRunnable.RmAllRunnable;
import org.openconcerto.utils.IFutureTask;
import org.openconcerto.utils.RecursionType;
import org.openconcerto.utils.SleepingQueue;
import org.openconcerto.utils.cc.IClosure;
import org.openconcerto.utils.cc.ITransformer;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.FutureTask;

import org.apache.commons.collections.CollectionUtils;

final class UpdateQueue extends SleepingQueue {

    /**
     * Whether the passed future performs an update.
     * 
     * @param f a task in this queue, can be <code>null</code>.
     * @return <code>true</code> if <code>f</code> loads from the db.
     */
    static boolean isUpdate(FutureTask<?> f) {
        return (f instanceof IFutureTask) && ((IFutureTask<?>) f).getRunnable() instanceof UpdateRunnable;
    }

    private static boolean isCancelableUpdate(FutureTask<?> f) {
        // don't cancel RmAll so we can put an UpdateAll right after it (the UpdateAll won't be
        // executed since RmAll put the queue to sleep)
        return isUpdate(f) && !(((IFutureTask<?>) f).getRunnable() instanceof RmAllRunnable);
    }

    private final class TableListener implements SQLTableModifiedListener, PropertyChangeListener {
        public void tableModified(SQLTableEvent evt) {
            if (UpdateQueue.this.alwaysUpdateAll)
                putUpdateAll();
            else if (evt.getMode() == Mode.ROW_UPDATED) {
                rowModified(evt);
            } else {
                rowAddedOrDeleted(evt);
            }
        }

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            // where changed
            putUpdateAll();
        }
    }

    private final ITableModel tableModel;
    private final TableListener tableListener;
    // TODO rm : needed for now since our optimizations are false if there's a where not on the
    // primary table, see http://192.168.1.10:3000/issues/show/22
    private boolean alwaysUpdateAll = false;

    public UpdateQueue(ITableModel model) {
        super(UpdateQueue.class.getSimpleName() + " on " + model);
        this.tableModel = model;
        this.tableListener = new TableListener();
        // savoir quand les tables qu'on affiche changent
        addTableListener();
    }

    void setAlwaysUpdateAll(boolean b) {
        this.alwaysUpdateAll = b;
    }

    // *** listeners

    @Override
    protected void dying() {
        this.rmTableListener();
        super.dying();
    }

    private void addTableListener() {
        for (final SQLTable t : this.tableModel.getReq().getTables()) {
            t.addTableModifiedListener(this.tableListener);
        }
        this.tableModel.getLinesSource().addListener(this.tableListener);
    }

    private void rmTableListener() {
        for (final SQLTable t : this.tableModel.getReq().getTables()) {
            t.removeTableModifiedListener(this.tableListener);
        }
        this.tableModel.getLinesSource().rmListener(this.tableListener);
    }

    // *** une des tables que l'on affiche a changé

    void rowModified(final SQLTableEvent evt) {
        final int id = evt.getId();
        if (id < SQLRow.MIN_VALID_ID) {
            this.putUpdateAll();
        } else if (CollectionUtils.containsAny(this.tableModel.getReq().getLineFields(), evt.getFields())) {
            this.put(evt);
        }
        // si on n'affiche pas le champ ignorer
    }

    // takes 1-2ms, perhaps cache
    final Set<SQLTable> getNotForeignTables() {
        final Set<SQLTable> res = new HashSet<SQLTable>();
        final SQLRowValues maxGraph = this.tableModel.getReq().getMaxGraph();
        maxGraph.getGraph().walk(maxGraph, res, new ITransformer<State<Set<SQLTable>>, Set<SQLTable>>() {
            @Override
            public Set<SQLTable> transformChecked(State<Set<SQLTable>> input) {
                if (input.getPath().length() == 0 || input.isBackwards())
                    input.getAcc().add(input.getCurrent().getTable());
                return input.getAcc();
            }
        }, RecursionType.BREADTH_FIRST, Direction.ANY);
        return res;
    }

    void rowAddedOrDeleted(final SQLTableEvent evt) {
        if (evt.getId() < SQLRow.MIN_VALID_ID)
            this.putUpdateAll();
        // if a row of a table that we point to is added, we will care when the referent table will
        // point to it
        else if (this.getNotForeignTables().contains(evt.getTable()))
            this.put(evt);
    }

    // *** puts

    private void put(SQLTableEvent evt) {
        this.put(UpdateRunnable.create(this.tableModel, evt));
    }

    public void putUpdateAll() {
        this.put(UpdateRunnable.create(this.tableModel));
    }

    /**
     * If this is sleeping, empty the list and call {@link #putUpdateAll()} so that the list reload
     * itself when this wakes up.
     * 
     * @throws IllegalStateException if not sleeping.
     */
    void putRemoveAll() {
        if (!this.isSleeping())
            throw new IllegalStateException("not sleeping");
        // no user runnables can come between the RmAll and the UpdateAll since runnableAdded()
        // is blocked by our lock, so there won't be any incoherence for them
        this.put(UpdateRunnable.createRmAll(this, this.tableModel));
        this.setSleeping(false);
        // reload the empty list when waking up
        this.putUpdateAll();
    }

    protected void willPut(final Runnable qr) throws InterruptedException {
        if (qr instanceof ChangeAllRunnable) {
            // si on met tout à jour, ne sert à rien de garder les maj précédentes.
            // ATTN aux runnables qui dépendent des update, si on enlève les maj
            // elles vont s'executer sans que sa maj soit faite
            this.tasksDo(new IClosure<Deque<FutureTask<?>>>() {
                @Override
                public void executeChecked(final Deque<FutureTask<?>> tasks) {
                    // on part de la fin et on supprime toutes les maj jusqu'a ce qu'on trouve
                    // un runnable qui n'est pas un UpdateRunnable
                    FutureTask<?> current = tasks.peekLast();
                    boolean onlyUpdateRunnable = true;
                    while (current != null && onlyUpdateRunnable) {
                        onlyUpdateRunnable = isCancelableUpdate(current);
                        if (onlyUpdateRunnable) {
                            tasks.removeLast();
                            current = tasks.peekLast();
                        }
                    }
                    if (onlyUpdateRunnable) {
                        final FutureTask<?> br = getBeingRun();
                        if (br != null && isCancelableUpdate(br))
                            br.cancel(true);
                    }
                }
            });
        }
    }

}

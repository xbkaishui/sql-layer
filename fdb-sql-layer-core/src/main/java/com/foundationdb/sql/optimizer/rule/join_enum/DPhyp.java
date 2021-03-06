/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.sql.optimizer.rule.join_enum;

import com.foundationdb.ais.model.Join;
import com.foundationdb.server.error.CorruptedPlanException;
import com.foundationdb.server.error.FailedJoinGraphCreationException;
import com.foundationdb.sql.optimizer.plan.*;
import static com.foundationdb.sql.optimizer.plan.JoinNode.JoinType;

import com.foundationdb.server.error.UnsupportedSQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/** 
 * Hypergraph-based dynamic programming for join ordering enumeration.
 * See "Dynamic Programming Strikes Back", doi:10.1145/1376616.1376672
 *
 * Summary:<ul>
 * <li>Nodes are joined tables.</li>
 * <li>Edges represent predicates and operator (LEFT, FULL OUTER,
 * SEMI, ...) reordering constraints.</li>
 * <li>DP happens by considering larger
 * sets made up from pairs of connected (based on edges) subsets.</li></ul>
 */
public abstract class DPhyp<P>
{
    private static final Logger logger = LoggerFactory.getLogger(DPhyp.class);

    // The leaves of the join tree: tables, derived tables, and
    // possibly joins handled atomically wrt this phase.
    private List<Joinable> tables;
    // The join operators, from JOINs and WHERE clause.
    private List<JoinOperator> operators, evaluateOperators, outsideOperators, oneSidedJoinOperators;
    // The hypergraph: since these are unordered, traversal pattern is
    // to go through in order pairing with adjacent (complement bit 1).
    private long[] edges;
    private long[] requiredSubgraphs;
    private int noperators, nedges;
    
    // Indexes for leaves and their constituent tables.
    private Map<Joinable,Long> tableBitSets;

    // The "plan class" is the set of retained plans for the given tables.
    private Object[] plans;
    
    @SuppressWarnings("unchecked")
    private P getPlan(long s) {
        return (P)plans[(int)s];
    }
    private void setPlan(long s, P plan) {
        if (logger.isTraceEnabled())
            logger.trace("{}: {}", JoinableBitSet.toString(s, tables), plan);
        plans[(int)s] = plan;
    }

    public P run(Joinable root, ConditionList whereConditions) {
        init(root, whereConditions);
        return solve();
    }

    /** Return minimal set of neighbors of <code>s</code> not in the exclusion set. */
    // TODO: Need to eliminate subsumed hypernodes.
    public long neighborhood(long s, long exclude) {
        exclude = JoinableBitSet.union(s, exclude);
        long result = 0;
        for (int e = 0; e < nedges; e++) {
            if (JoinableBitSet.isSubset(edges[e], s) &&
                !JoinableBitSet.overlaps(edges[e^1], exclude)) {
                result |= JoinableBitSet.minSubset(edges[e^1]);
            }
        }
        return result;
    }

    /** Run dynamic programming and return best overall plan(s). */
    public P solve() {
        int ntables = tables.size();
        assert (ntables < 31);
        plans = new Object[1 << ntables];
        for (int pass = 1; pass <= 2; pass++) {
            // Start with single tables.
            for (int i = 0; i < ntables; i++) {
                long bitset = JoinableBitSet.of(i);
                setPlan(bitset, evaluateTable(bitset, tables.get(i)));
            }
            for (int i = ntables - 1; i >= 0; i--) {
                // Like the outer loop, two passes here, should work.
                for (int j=0; j < 2; j++) {
                    if (emitAndEnumerateCsg(i)) {
                        break;
                    }
                    if (j > 0) {
                        throw new FailedJoinGraphCreationException();
                    }
                }
            }
            P plan = getPlan(plans.length-1); // One that does all the joins together.
            if (plan != null)
                return plan;
            if (logger.isTraceEnabled()) {
                StringBuilder str = new StringBuilder("Plan not complete");
                for (int i = 1; i < plans.length-1; i++) {
                    if (plans[i] != null) {
                        str.append('\n').append(Integer.toString(i, 2))
                           .append(": ").append(plans[i]);
                    }
                }
                logger.trace("{}", str.toString());
            }
            // dphyp should be able to create a graph so long as it's not a true cross product
            // (ie. join with no conditions)
            if (pass != 1) {
                throw new FailedJoinGraphCreationException();
            }
            addExtraEdges();
            Arrays.fill(plans, null);
        }
        return null;
    }

    public boolean emitAndEnumerateCsg(int i) {
        boolean retVal = true;
        long ts = JoinableBitSet.of(i);
        emitCsg(ts);
        enumerateCsgRec(ts, JoinableBitSet.through(i));
        for (int j=0; j<requiredSubgraphs.length; j++) {
            long requiredSubgraph = requiredSubgraphs[j];
            if (JoinableBitSet.equals(ts, JoinableBitSet.minSubset(requiredSubgraph))) {
                if (getPlan(requiredSubgraph) == null) {
                    addExtraEdges(requiredSubgraph);
                    retVal = false;
                }
            }
        }
        return retVal;
    }

    /** Recursively extend the given connected subgraph. */
    public void enumerateCsgRec(long s1, long exclude) {
        long neighborhood = neighborhood(s1, exclude);
        if (neighborhood == 0) return;
        long subset = 0;
        do {
            subset = JoinableBitSet.nextSubset(subset, neighborhood);
            long next = JoinableBitSet.union(s1, subset);
            if (getPlan(next) != null)
                emitCsg(next);
        } while (!JoinableBitSet.equals(subset, neighborhood));
        subset = 0;             // Start over.
        exclude = JoinableBitSet.union(exclude, neighborhood);
        do {
            subset = JoinableBitSet.nextSubset(subset, neighborhood);
            long next = JoinableBitSet.union(s1, subset);
            enumerateCsgRec(next, exclude);
        } while (!JoinableBitSet.equals(subset, neighborhood));
    }

    /** Generate seeds for connected complements of the given connected subgraph. */
    public void emitCsg(long s1) {
        long exclude = JoinableBitSet.union(s1, JoinableBitSet.through(JoinableBitSet.min(s1)));
        long neighborhood = neighborhood(s1, exclude);
        if (neighborhood == 0) return;
        for (int i = tables.size() - 1; i >= 0; i--) {
            long s2 = JoinableBitSet.of(i);
            if (JoinableBitSet.overlaps(neighborhood, s2)) {
                boolean connected = false;
                for (int e = 0; e < nedges; e+=2) {
                    if (isEvaluateOperator(s1, s2, e)) {
                        connected = true;
                        break;
                    }
                }
                if (connected)
                    emitCsgCmp(s1, s2);
                enumerateCmpRec(s1, s2, exclude);
            }
        }        
    }

    /** Extend complement <code>s2</code> until a csg-cmp pair is
     * reached, excluding the given tables to avoid duplicate
     * enumeration, */
    public void enumerateCmpRec(long s1, long s2, long exclude) {
        long neighborhood = neighborhood(s2, exclude);
        if (neighborhood == 0) return;
        long subset = 0;
        do {
            subset = JoinableBitSet.nextSubset(subset, neighborhood);
            long next = JoinableBitSet.union(s2, subset);
            if (getPlan(next) != null) {
                boolean connected = false;
                for (int e = 0; e < nedges; e+=2) {
                    if (isEvaluateOperator(s1, next, e)) {
                        connected = true;
                        break;
                    }
                }
                if (connected)
                    emitCsgCmp(s1, next);
            }
        } while (!JoinableBitSet.equals(subset, neighborhood));
        subset = 0;             // Start over.
        exclude = JoinableBitSet.union(exclude, neighborhood);
        do {
            subset = JoinableBitSet.nextSubset(subset, neighborhood);
            long next = JoinableBitSet.union(s2, subset);
            enumerateCmpRec(s1, next, exclude);
        } while (!JoinableBitSet.equals(subset, neighborhood));
    }

    /** Emit the connected subgraph / complement pair.
     * That is, build and cost a plan that joins them and maybe
     * register it as the new best such plan for the pair.
     */
    public void emitCsgCmp(long s1, long s2) {
        P p1 = getPlan(s1);
        P p2 = getPlan(s2);
        long s = JoinableBitSet.union(s1, s2);
        JoinType join12 = JoinType.INNER, join21 = JoinType.INNER;
        evaluateOperators.clear();
        oneSidedJoinOperators.clear();
        boolean connected = false;
        for (int e = 0; e < nedges; e +=2) {
            boolean isEvaluate = isEvaluateOperator(s1, s2, e);
            boolean isRelevant = isRelevant(s1, s2, e);
            connected |= isEvaluate;
            if (isEvaluate || isRelevant) {
                // The one that produced this edge.
                JoinOperator operator = operators.get(e/2);
                JoinType joinType = operator.getJoinType();
                if (joinType != JoinType.INNER) {
                    join12 = joinType;
                    join21 = commuteJoinType(joinType);
                }
                evaluateOperators.add(operator);
            }
        }
        if (!connected) {
            return;
        }
        outsideOperators.clear();
        for (JoinOperator operator : operators) {
            if (JoinableBitSet.overlaps(operator.predicateTables, s) &&
                !JoinableBitSet.isSubset(operator.predicateTables, s) &&
                !evaluateOperators.contains(operator))
                // An operator involving tables in this join with others.
                outsideOperators.add(operator);
        }
        P plan = getPlan(s);
        if (join12 != null)
            plan = evaluateJoin(s1, p1, s2, p2, s, plan, 
                                join12, evaluateOperators, outsideOperators);
        if (join21 != null)
            plan = evaluateJoin(s2, p2, s1, p1, s, plan, 
                                join21, evaluateOperators, outsideOperators);
        setPlan(s, plan);
    }

    /**
     * This checks if all tables necessary for an edge are available and that
     * it is the edge's first time appearing
     *
     * EXPLAIN:
     * cases:
     * no sided edge gets true only on first join
     * one sided edges are only added first time table is available
     * if both sides of the edge exist on one side of the join already it must have already been added
     * else if both sides connect both sets then true
     */
    private boolean isRelevant(long s1, long s2, int e) {
        if ((JoinableBitSet.count(s1) == 1 && JoinableBitSet.count(s2) == 1) &&
                (JoinableBitSet.isEmpty(edges[e]) && JoinableBitSet.isEmpty(edges[e ^ 1]))) {
            return true;
        }
        if (JoinableBitSet.count(s1) == 1 && ((edges[e] | edges[e ^ 1]) == s1)) {
            return true;
        }
        if (JoinableBitSet.count(s2) == 1 && ((edges[e] | edges[e ^ 1]) == s2))
            return true;
        if (JoinableBitSet.isSubset(edges[e] | edges[e ^ 1], s1) || JoinableBitSet.isSubset(edges[e] | edges[e ^ 1], s2)) {
            return false;
        }
        return JoinableBitSet.isSubset(edges[e] | edges[e ^ 1], s1 | s2);
    }

    /**
     * This checks if the edge connects two unconnected sets
     *
     * EXPLAIN:
     * cases false if either side of edge is empty since it cannot connect anything
     * this must be done because the empty set is the subset of all sets
     * if edge connects the two sides return true
     */
    public boolean isEvaluateOperator(long s1, long s2, int e) {
        if (JoinableBitSet.isEmpty(edges[e]) || JoinableBitSet.isEmpty(edges[e ^ 1])) {
            return false;
        }
        return JoinableBitSet.isSubset(edges[e], s1) && JoinableBitSet.isSubset(edges[e ^ 1], s2)   ||
                JoinableBitSet.isSubset(edges[e], s2) && JoinableBitSet.isSubset(edges[e ^ 1], s1);
    }

    /** Return the best plan for the one-table initial state. */
    public abstract P evaluateTable(long bitset, Joinable table);

    /** Adjust best plan <code>existing</code> for the join of
     * <code>p1</code> and <code>p2</code> with given type and
     * conditions taken from the given joins.
     */
    public abstract P evaluateJoin(long bitset1, P p1, long bitset2, P p2, long bitsetJoined, P existing,
                                   JoinType joinType, Collection<JoinOperator> joins, Collection<JoinOperator> outsideJoins);

    /** Return a leaf of the tree. */
    public Joinable getTable(int index) {
        return tables.get(index);
    }
    
    public long getTableBit (Joinable join) {
        return tableBitSets.get(join);
    }
    
    public Map<Joinable, Long> getTableBitSets() {
        return tableBitSets;
    }

    public long rootJoinLeftTables() {
        JoinOperator lastOp = null;
        for (JoinOperator op : operators) {
            if (op.getJoin() == null) break;
            lastOp = op;
        }
        return lastOp.leftTables;
    }
    /** Initialize state from the given join tree. */
    // TODO: Need to do something about disconnected overall. The
    // cross-product isn't going to do very well no matter what. Maybe
    // just break up the graph _before_ calling all this?
    public void init(Joinable root, ConditionList whereConditions) {
        tables = new ArrayList<>();
        addTables(root, tables);
        int ntables = tables.size();
        if (ntables > 30)
            // TODO: Need to select some simpler algorithm that scales better.
            throw new UnsupportedSQLException("Too many tables in query: " + ntables, 
                                              null);
        tableBitSets = new HashMap<>(ntables);
        for (int i = 0; i < ntables; i++) {
            Joinable table = tables.get(i);
            Long bitset = JoinableBitSet.of(i);
            tableBitSets.put(table, bitset);
            if (table instanceof TableJoins) {
                for (TableSource joinedTable : ((TableJoins)table).getTables()) {
                    tableBitSets.put(joinedTable, bitset);
                }
            }
            else if (table instanceof TableGroupJoinTree) {
                for (TableGroupJoinTree.TableGroupJoinNode node : (TableGroupJoinTree)table) {
                    tableBitSets.put(node.getTable(), bitset);
                }
            }
        }
        ExpressionTables visitor = new ExpressionTables(tableBitSets);
        noperators = 0;
        JoinOperator rootOp = initSES(root, visitor);
        if (whereConditions != null)
            noperators += whereConditions.size(); // Maximum possible addition.
        operators = new ArrayList<>(noperators);
        nedges = noperators * 2;
        edges = new long[nedges];
        calcTES(rootOp);
        noperators = operators.size();
        if (whereConditions != null)
            addWhereConditions(whereConditions, visitor, JoinableBitSet.empty());
        int iop = 0;
        requiredSubgraphs = new long[operators.size()];
        int irs = 0;
        for (JoinOperator joinOperator : operators) {
            if (!joinOperator.getJoinType().isRightLinear()) {
                if (JoinableBitSet.count(joinOperator.rightTables) > 1) {
                    requiredSubgraphs[irs] = joinOperator.rightTables;
                    irs++;
                }
            } else if (!joinOperator.getJoinType().isLeftLinear()) {
                // must be a right join
                throw new CorruptedPlanException("RIGHT OUTER JOIN was not converted to LEFT OUTER JOIN before dphyp");
            }
        }
        requiredSubgraphs = Arrays.copyOf(requiredSubgraphs, irs);
        while (iop < noperators) {
            JoinOperator op = operators.get(iop);
            if (op.allInnerJoins) {
                // Inner joins can treat join conditions only among them like WHERE
                // conditions, as independent edges.
                if (op.joinConditions != null) {
                    // Join conditions for an INNER subtree.
                    addJoinConditions(op.joinConditions, visitor,
                            JoinableBitSet.empty());
                }
                if ((op.parent != null) && !op.parent.allInnerJoins &&
                    (op.parent.joinConditions != null)) {
                    // Parent join conditions for one side that's INNER.
                    long otherSide = (op == op.parent.left) ?
                        op.parent.rightTables :
                        op.parent.leftTables;
                    addJoinConditions(op.parent.joinConditions, visitor, otherSide);
                }
                if (JoinableBitSet.isEmpty(op.tes)) {
                    // Remove this operator that won't contribute any edges.
                    operators.remove(iop);
                    noperators--;
                    System.arraycopy(edges, (iop + 1) * 2,
                                     edges, iop * 2,
                                     (operators.size() - iop) * 2);
                    continue;
                }
            }
            iop++;
        }
        noperators = operators.size();
        nedges = noperators * 2;
        if (logger.isTraceEnabled()) {
            StringBuilder str = new StringBuilder("Operators and edges:");
            for (int i = 0; i < noperators; i++) {
                str.append('\n')
                   .append(operators.get(i).toString(tables)).append('\n')
                   .append(JoinableBitSet.toString(edges[i*2], tables)).append(" <-> ")
                   .append(JoinableBitSet.toString(edges[i*2+1], tables));
            }
            logger.trace("{}", str.toString());
        }
        evaluateOperators = new ArrayList<>(noperators);
        outsideOperators = new ArrayList<>(noperators);
        oneSidedJoinOperators = new ArrayList<>(noperators);
    }

    public static void addTables(Joinable n, List<Joinable> tables) {
        if (n instanceof JoinNode) {
            JoinNode join = (JoinNode)n;
            addTables(join.getLeft(), tables);
            addTables(join.getRight(), tables);
        }
        else {
            tables.add(n);
        }
    }

    public static class JoinOperator {
        JoinNode join;          // If from an actual join.
        ConditionList joinConditions;
        JoinOperator left, right, parent;
        long leftTables, rightTables, predicateTables;
        long tes;
        boolean allInnerJoins;
        
        public JoinOperator(JoinNode join) {
            this.join = join;
            joinConditions = join.getJoinConditions() == null ? null : new ConditionList(join.getJoinConditions());
            allInnerJoins = (join.getJoinType() == JoinType.INNER);
        }

        public JoinOperator(ConditionExpression condition, long leftTables, long rightTables) {
            joinConditions = new ConditionList(1);
            joinConditions.add(condition);
            this.leftTables = leftTables;
            this.rightTables = rightTables;
            this.tes = this.predicateTables = this.getTables();
        }

        public JoinOperator() {
            joinConditions = new ConditionList(0);
        }

        public JoinOperator(JoinOperator join) {
            this.join = join.join;
            this.joinConditions = join.joinConditions == null ? null : new ConditionList(join.joinConditions);
            this.left = join.left;
            this.right = join.right;
            this.parent = join.parent;
            leftTables = join.leftTables;
            rightTables = join.rightTables;
            predicateTables = join.predicateTables;
            tes = join.tes;
            allInnerJoins = join.allInnerJoins;
        }

        public long getTables() {
            return JoinableBitSet.union(leftTables, rightTables);
        }

        public JoinNode getJoin() {
            return join;
        }
        public ConditionList getJoinConditions() {
            return joinConditions;
        }

        /**
         * Moves conditions up from the child as appropriate based on the child's type
         * No checks for this operators linear type are checked.
         * @param child either this.left or this.right.
         * @return the operator from which joinConditions are taken.
         */
        public JoinOperator moveJoinConditionsUp(JoinOperator destination, JoinOperator child, ExpressionTables visitor) {
            if (child.getJoinType().isFullyLinear()) {
                destination.moveJoinConditions(child, visitor);
                return child;
            } else if (child.getJoinType().isLeftLinear() && child.left != null) {
                // We recurse here, because you may have
                // ((((t1 inner t2 on X) left t3) left t4) inner t5)
                // which is the same as:
                // ((((t1 inner t2) left t3) left t4) inner t5 on X)
                return moveJoinConditionsUp(destination, child.left, visitor);
            } else if (child.getJoinType().isRightLinear() && child.right != null) {
                return moveJoinConditionsUp(destination, child.right, visitor);
            }
            // child is neither left or right linear, must be a full outer join, no condition movement
            // allowed
            return null;
        }

        private void moveJoinConditions(JoinOperator other, ExpressionTables visitor) {
            if (other != null && other.joinConditions != null && !other.joinConditions.isEmpty()) {
                if (joinConditions == null) {
                    joinConditions = new ConditionList();
                }
                boolean movedSomething = false;
                for (Iterator<ConditionExpression> iterator = other.joinConditions.iterator(); iterator.hasNext(); ) {
                    ConditionExpression condition = iterator.next();
                    long conditionTes = visitor.getTables(condition);
                    if (!JoinableBitSet.isSubset(conditionTes, other.getTables())) {
                        movedSomething = true;
                        joinConditions.add(condition);
                        iterator.remove();
                    }
                }
                if (movedSomething) {
                    updateTes(visitor);
                }
            }
        }

        private void updateTes(ExpressionTables visitor) {
            predicateTables = visitor.getTables(joinConditions);
            if (visitor.wasNullTolerant() && !allInnerJoins)
                tes = getTables();
            else
                tes = JoinableBitSet.intersection(getTables(), predicateTables);
        }

        public JoinType getJoinType() {
            if (join != null)
                return join.getJoinType();
            else
                return JoinType.INNER;
        }

        public String toString(List<Joinable> tables) {
            return toString();
        }

        public String toString() {
            if (join != null)
                return join.toString();
            else
                return joinConditions.toString();
        }
    }

    /** Starting state of the TES is just those tables used syntactically. */
    protected JoinOperator initSES(Joinable n, ExpressionTables visitor) {
        if (n instanceof JoinNode) {
            JoinNode join = (JoinNode)n;
            JoinOperator op = new JoinOperator(join);
            if (op.getJoinType() == JoinType.RIGHT) {
                throw new CorruptedPlanException("RIGHT OUTER JOIN was not converted to LEFT OUTER JOIN before dphyp");
            }
            Joinable left = join.getLeft();
            JoinOperator leftOp = initSES(left, visitor);
            boolean childAllInnerJoins = false;
            if (leftOp != null) {
                leftOp.parent = op;
                op.left = leftOp;
                op.leftTables = leftOp.getTables();
                if (leftOp.allInnerJoins)
                    childAllInnerJoins = true;
                else
                    op.allInnerJoins = false;
                if (op.getJoinType().isRightLinear()) {
                    op.moveJoinConditionsUp(op, op.left, visitor);
                }
            }
            else {
                op.leftTables = getTableBit(left);
            }
            Joinable right = join.getRight();
            JoinOperator rightOp = initSES(right, visitor);
            if (rightOp != null) {
                rightOp.parent = op;
                op.right = rightOp;
                op.rightTables = rightOp.getTables();
                if (rightOp.allInnerJoins)
                    childAllInnerJoins = true;
                else
                    op.allInnerJoins = false;
                if (op.getJoinType().isLeftLinear()) {
                    op.moveJoinConditionsUp(op, op.right, visitor);
                }
            }
            else {
                op.rightTables = getTableBit(right); 
            }
            op.updateTes(visitor);
            noperators++;
            if ((op.joinConditions != null) && (op.allInnerJoins || childAllInnerJoins))
                noperators += op.joinConditions.size(); // Might move some.
            return op;
        }
        return null;
    }

    /** Extend TES for join operators based on reordering conflicts. */
    public void calcTES(JoinOperator op) {
        if (op != null) {
            calcTES(op.left);
            calcTES(op.right);
            addConflicts(op, op.left, true);
            addConflicts(op, op.right, false);
            long r = op.rightTables;
            long l = JoinableBitSet.difference(op.tes, r);
            int o = operators.size();
            operators.add(op);
            // Add an edge for the TES of this join operator.
            edges[o*2] = l;
            edges[o*2+1] = r;
        }
    }

    /** Add conflicts to <code>o1</code> from descendant <code>o2</code>. */
    protected static void addConflicts(JoinOperator o1, JoinOperator o2, boolean left) {
        if (o2 != null) {
            if (left ? leftConflict(o2, o1) : rightConflict(o1, o2)) {
                o1.tes = JoinableBitSet.union(o1.tes, o2.tes);
            }
            addConflicts(o1, o2.left, left);
            addConflicts(o1, o2.right, left);
        }
    }

    /** Is there a left ordering conflict? */
    protected static boolean leftConflict(JoinOperator o2, JoinOperator o1) {
        return JoinableBitSet.overlaps(o1.predicateTables, rightTables(o1, o2)) &&
            operatorConflict(o2.getJoinType(), o1.getJoinType());
    }

    /** Is there a right ordering conflict? */
    protected static boolean rightConflict(JoinOperator o1, JoinOperator o2) {
        return JoinableBitSet.overlaps(o1.predicateTables, leftTables(o1, o2)) &&
            operatorConflict(o1.getJoinType(), o2.getJoinType());
    }

    /** Does parent operator <code>o1</code> conflict with child <code>o2</code>? */
    protected static boolean operatorConflict(JoinType o1, JoinType o2) {
        switch (o1) {
        case INNER:
            return (o2 == JoinType.FULL_OUTER);
        case LEFT:
            return (o2 != JoinType.LEFT);
        case FULL_OUTER:
            return (o2 == JoinType.INNER);
        case RIGHT:
            assert false;       // Should not see right join at this point.
        default:
            return true;
        }
    }

    /** All the left tables on the path from <code>o2</code> (inclusive) 
     * to <code>o1</code> (exclusive). */
    protected static long leftTables(JoinOperator o1, JoinOperator o2) {
        long result = JoinableBitSet.empty();
        for (JoinOperator o3 = o2; o3 != o1; o3 = o3.parent)
            result = JoinableBitSet.union(result, o3.leftTables);
        if (isCommutative(o2.getJoinType()))
            result = JoinableBitSet.union(result, o2.rightTables);
        return result;
    }

    /** All the right tables on the path from <code>o2</code> (inclusive) 
     * to <code>o1</code> (exclusive). */
    protected static long rightTables(JoinOperator o1, JoinOperator o2) {
        long result = JoinableBitSet.empty();
        for (JoinOperator o3 = o2; o3 != o1; o3 = o3.parent)
            result = JoinableBitSet.union(result, o3.rightTables);
        if (isCommutative(o2.getJoinType()))
            result = JoinableBitSet.union(result, o2.leftTables);
        return result;
    }

    /** Does this operator commute? */
    protected static boolean isCommutative(JoinType joinType) {
        return (commuteJoinType(joinType) != null);
    }

    protected static JoinType commuteJoinType(JoinType joinType) {
        switch (joinType) {
        case INNER:
        case FULL_OUTER:
            return joinType;
        case SEMI_INNER_ALREADY_DISTINCT:
            return JoinType.INNER;
        case SEMI_INNER_IF_DISTINCT:
            return JoinType.INNER_NEED_DISTINCT;
        default:
            return null;
        }
    }

    /** Get join conditions from join clauses. */
    protected void addJoinConditions(ConditionList whereConditions,
                                      ExpressionTables visitor,
                                      long excludeTables) {
        Iterator<ConditionExpression> iter = whereConditions.iterator();
        // NOTE: conditions on the join clause must be added somewhere when initializing the operators,
        // or they won't end up in the resulting plan.
        while (iter.hasNext()) {
            ConditionExpression condition = iter.next();
            if (condition instanceof ComparisonCondition) {
                ComparisonCondition comp = (ComparisonCondition)condition;
                long columnTables = columnReferenceTable(comp.getLeft());
                if (!JoinableBitSet.isEmpty(columnTables) &&
                        !JoinableBitSet.overlaps(columnTables, excludeTables)) {
                    long rhs = visitor.getTables(comp.getRight());
                    if (visitor.wasNullTolerant() ||
                            JoinableBitSet.overlaps(rhs, excludeTables))
                        continue;
                    if (addInnerJoinCondition(condition, columnTables, rhs)) {
                        iter.remove();
                        continue;
                    }
                }
                columnTables = columnReferenceTable(comp.getRight());
                if (!JoinableBitSet.isEmpty(columnTables) &&
                        !JoinableBitSet.overlaps(columnTables, excludeTables)) {
                    long lhs = visitor.getTables(comp.getLeft());
                    if (visitor.wasNullTolerant() ||
                            JoinableBitSet.overlaps(lhs, excludeTables))
                        continue;
                    if (addInnerJoinCondition(condition, columnTables, lhs)) {
                        iter.remove();
                        continue;
                    }
                }
            } else {
                // NOTE: this could be something weird like f(c1,c2,c3,c4) = 5, I'm just going to put the first
                // source on the left, and the rest on the right.
                // TODO the dphyper.pdf explains how to handle this situation, by switch to having edges be of the form
                // (u,v,w) where u and v are conditions on the left or right side respectively, but the w conditions can
                // be on either side.
                long tables = visitor.getTables(condition);
                long left = JoinableBitSet.minSubset(tables);
                long remaining = JoinableBitSet.difference(tables, left);
                if (!JoinableBitSet.overlaps(tables, excludeTables)) {
                    if (addInnerJoinCondition(condition, left, remaining)) {
                        iter.remove();
                    }
                }

            }
        }
    }


    /** Add an edge for the tables in this simple condition.
     * This only applies to conditions that appear on an inner join
     */
    protected boolean addInnerJoinCondition(ConditionExpression condition,
                                            long columnTables, long comparisonTables) {
        if (!JoinableBitSet.overlaps(columnTables, comparisonTables)) {
            JoinOperator op = new JoinOperator(condition, columnTables, comparisonTables);
            int o = operators.size();
            operators.add(op);
            edges[o*2] = columnTables;
            edges[o*2+1] = comparisonTables;
            return true;
        }
        return false;
    }

    /** Get join conditions from top-level WHERE predicates. */
    protected void addWhereConditions(ConditionList whereConditions, 
                                      ExpressionTables visitor,
                                      long excludeTables) {
        Iterator<ConditionExpression> iter = whereConditions.iterator();
        while (iter.hasNext()) {
            ConditionExpression condition = iter.next();
            // TODO: When optimizer supports more predicates
            // interestingly, can recognize them, including
            // generalized hypergraph triples.
            if (condition instanceof ComparisonCondition) {
                ComparisonCondition comp = (ComparisonCondition)condition;
                long columnTables = columnReferenceTable(comp.getLeft());
                if (!JoinableBitSet.isEmpty(columnTables) &&
                    !JoinableBitSet.overlaps(columnTables, excludeTables)) {
                    long rhs = visitor.getTables(comp.getRight());
                    if (visitor.wasNullTolerant() ||
                        JoinableBitSet.overlaps(rhs, excludeTables))
                        continue;
                    if (addWhereCondition(condition, columnTables, rhs)) {
                        iter.remove();
                        continue;
                    }
                }
                columnTables = columnReferenceTable(comp.getRight());
                if (!JoinableBitSet.isEmpty(columnTables) &&
                    !JoinableBitSet.overlaps(columnTables, excludeTables)) {
                    long lhs = visitor.getTables(comp.getLeft());
                    if (visitor.wasNullTolerant() ||
                        JoinableBitSet.overlaps(lhs, excludeTables))
                        continue;
                    if (addWhereCondition(condition, columnTables, lhs)) {
                        iter.remove();
                        continue;
                    }
                }
            }
        }
    }

    /** Is this a single column in a known table? */
    protected long columnReferenceTable(ExpressionNode node) {
        if (node instanceof ColumnExpression) {
            Long bitset = tableBitSets.get(((ColumnExpression)node).getTable());
            if (bitset != null) {
                return bitset;
            }
        }
        return JoinableBitSet.empty();
    }

    /** Add an edge for the tables in this simple condition. 
     * There are no extra reordering constraints, since this is just a
     * WHERE condition. Furthermore, its tables ought to be in INNER
     * joins, since such a null-intolerent condition implies that they
     * aren't missing.
     */
    protected boolean addWhereCondition(ConditionExpression condition,
                                        long columnTables, long comparisonTables) {
        if (!JoinableBitSet.isEmpty(comparisonTables) &&
            !JoinableBitSet.overlaps(columnTables, comparisonTables)) {
            JoinOperator op = new JoinOperator(condition, columnTables, comparisonTables);
            int o = operators.size();
            operators.add(op);
            edges[o*2] = columnTables;
            edges[o*2+1] = comparisonTables;
            return true;
        }
        return false;
    }

    /** Compute tables used in join predicate. */
    public static class ExpressionTables implements ExpressionVisitor, PlanVisitor {
        Map<Joinable,Long> tableBitSets;
        long result;
        boolean nullTolerant;

        public ExpressionTables(Map<Joinable,Long> tableBitSets) {
            this.tableBitSets = tableBitSets;
        }

        public long getTables(ConditionList conditions) {
            result = JoinableBitSet.empty();
            nullTolerant = false;
            if (conditions != null) {
                for (ConditionExpression cond : conditions) {
                    cond.accept(this);
                }
            }
            return result;
        }

        public long getTables(ExpressionNode node) {
            result = JoinableBitSet.empty();
            nullTolerant = false;
            node.accept(this);
            return result;
        }

        public boolean wasNullTolerant() {
            return nullTolerant;
        }

        @Override
        public boolean visitEnter(ExpressionNode n) {
            return visit(n);
        }

        @Override
        public boolean visitLeave(ExpressionNode n) {
            return true;
        }

        @Override
        public boolean visit(ExpressionNode n) {
            if (n instanceof ColumnExpression) {
                Long bitset = tableBitSets.get(((ColumnExpression)n).getTable());
                if (bitset != null) {
                    result = JoinableBitSet.union(result, bitset);
                }
            }
            else if (!nullTolerant && (n instanceof FunctionExpression)) {
                String fname = ((FunctionExpression)n).getFunction();
                if ("isNull".equals(fname) || "isUnknown".equals(fname) ||
                    "isTrue".equals(fname) || "isFalse".equals(fname) ||
                    "COALESCE".equals(fname) || "ifnull".equals(fname))
                    nullTolerant = true;
            }
            return true;
        }

        @Override
        public boolean visitEnter(PlanNode n) {
            return visit(n);
        }

        @Override
        public boolean visitLeave(PlanNode n) {
            return true;
        }

        @Override
        public boolean visit(PlanNode n) {
            return true;
        }
    }
    
    /**
     * Add edges to make the hypergraph connected, using the stuck state.
     * @param maxGraph Will only add extra edges to connect up the maxGraph
     */
    protected void addExtraEdges(long maxGraph) {
        // Get all the hypernodes that are not subsets of some filled hypernode.
        BitSet maximal = new BitSet(plans.length);
        for (int i = 0; i < plans.length; i++) {
            if (plans[i] != null && JoinableBitSet.isSubset(i,maxGraph)) {
                maximal.set(i);
            }
        }
        for (int i = plans.length - 1; i > 2; i--) { // (3 is the smallest with subsets)
            if (maximal.get(i)) {
                long subset = JoinableBitSet.empty();
                while (true) {
                    subset = JoinableBitSet.nextSubset(subset, i);
                    if (JoinableBitSet.equals(subset, i)) break;
                    maximal.clear((int)subset);
                }
            }
        }
        int count = maximal.cardinality();
        assert (count > 1) : "Found less than 2 unconnected subgraphs";
        noperators += count - 1;
        nedges = noperators * 2;
        if (edges.length < nedges) { // Length was a conservative guess and might be large enough already.
            long[] newEdges = new long[nedges];
            System.arraycopy(edges, 0, newEdges, 0, edges.length);
            edges = newEdges;
        }
        // Just connect them all up. This is by no means an optimal
        // set of new edges, but the plan is trouble to begin with
        // since it involved a cross product. Would need something
        // like a min cut hypergraph partition.
        long left = JoinableBitSet.empty();
        int i = -1;
        while (true) {
            i = maximal.nextSetBit(i+1);
            if (i < 0) break;
            if (JoinableBitSet.isEmpty(left)) {
                left = i;
            }
            else {
                addExtraEdge(left, i);
                left = JoinableBitSet.union(left, i);
            }
        }
        assert (noperators == operators.size());
    }


    private void addExtraEdges() {
        addExtraEdges(plans.length-1);
    }

    protected void addExtraEdge(long left, long right) {
        if (logger.isTraceEnabled())
            logger.trace("Extra: {}  <->  {}",
                         JoinableBitSet.toString(left, tables) +
                         JoinableBitSet.toString(right, tables));
        JoinOperator op = new JoinOperator();
        op.leftTables = left;
        op.rightTables = right;
        int o = operators.size();
        operators.add(op);
        edges[o*2] = left;
        edges[o*2+1] = right;
    }

}

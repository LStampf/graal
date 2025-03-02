/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.graal.compiler.nodes.java;

import static jdk.graal.compiler.core.common.calc.CanonicalCondition.EQ;
import static jdk.graal.compiler.debug.DebugContext.DETAILED_LEVEL;
import static jdk.graal.compiler.nodeinfo.InputType.Memory;
import static jdk.graal.compiler.nodeinfo.InputType.Value;
import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_8;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_8;

import jdk.graal.compiler.core.common.memory.MemoryOrderMode;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.virtual.VirtualArrayNode;
import jdk.graal.compiler.nodes.virtual.VirtualInstanceNode;
import jdk.graal.compiler.nodes.virtual.VirtualObjectNode;
import jdk.graal.compiler.nodes.LogicConstantNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.calc.CompareNode;
import jdk.graal.compiler.nodes.calc.ConditionalNode;
import jdk.graal.compiler.nodes.calc.ObjectEqualsNode;
import jdk.graal.compiler.nodes.memory.AbstractMemoryCheckpoint;
import jdk.graal.compiler.nodes.memory.OrderedMemoryAccess;
import jdk.graal.compiler.nodes.memory.SingleMemoryKill;
import jdk.graal.compiler.nodes.spi.Lowerable;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;
import org.graalvm.word.LocationIdentity;

import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaField;

@NodeInfo(allowedUsageTypes = {Value, Memory}, cycles = CYCLES_8, size = SIZE_8)
public abstract class AbstractUnsafeCompareAndSwapNode extends AbstractMemoryCheckpoint implements OrderedMemoryAccess, Lowerable, SingleMemoryKill, Virtualizable {
    public static final NodeClass<AbstractUnsafeCompareAndSwapNode> TYPE = NodeClass.create(AbstractUnsafeCompareAndSwapNode.class);
    @Input ValueNode object;
    @Input ValueNode offset;
    @Input ValueNode expected;
    @Input ValueNode newValue;
    protected final JavaKind valueKind;
    protected final LocationIdentity locationIdentity;
    protected final MemoryOrderMode memoryOrder;

    public AbstractUnsafeCompareAndSwapNode(NodeClass<? extends AbstractMemoryCheckpoint> c, Stamp stamp, ValueNode object, ValueNode offset, ValueNode expected, ValueNode newValue,
                    JavaKind valueKind, LocationIdentity locationIdentity, MemoryOrderMode memoryOrder) {
        super(c, stamp);
        this.object = object;
        this.offset = offset;
        this.expected = expected;
        this.newValue = newValue;
        this.valueKind = valueKind;
        this.locationIdentity = locationIdentity;
        this.memoryOrder = memoryOrder;
    }

    public ValueNode object() {
        return object;
    }

    public ValueNode offset() {
        return offset;
    }

    public ValueNode expected() {
        return expected;
    }

    public ValueNode newValue() {
        return newValue;
    }

    public JavaKind getValueKind() {
        return valueKind;
    }

    @Override
    public MemoryOrderMode getMemoryOrder() {
        return memoryOrder;
    }

    @Override
    public LocationIdentity getKilledLocationIdentity() {
        return ordersMemoryAccesses() ? LocationIdentity.ANY_LOCATION : locationIdentity;
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        ValueNode offsetAlias = tool.getAlias(offset);
        if (!offsetAlias.isJavaConstant()) {
            return;
        }
        long constantOffset = offsetAlias.asJavaConstant().asLong();
        ValueNode objectAlias = tool.getAlias(object);
        int index;
        if (objectAlias instanceof VirtualInstanceNode) {
            VirtualInstanceNode obj = (VirtualInstanceNode) objectAlias;

            ResolvedJavaField field = obj.type().findInstanceFieldWithOffset(constantOffset, expected.getStackKind());
            if (field == null) {
                tool.getDebug().log(DETAILED_LEVEL, "%s.virtualize() -> Unknown field", this);
                return;
            }
            index = obj.fieldIndex(field);
        } else if (objectAlias instanceof VirtualArrayNode) {
            VirtualArrayNode array = (VirtualArrayNode) objectAlias;
            index = array.entryIndexForOffset(tool.getMetaAccess(), constantOffset, valueKind);
        } else {
            return;
        }
        if (index < 0) {
            tool.getDebug().log(DETAILED_LEVEL, "%s.virtualize() -> Unknown index", this);
            return;
        }
        VirtualObjectNode obj = (VirtualObjectNode) objectAlias;
        ValueNode currentValue = tool.getEntry(obj, index);
        ValueNode expectedAlias = tool.getAlias(this.expected);

        LogicNode equalsNode = null;
        if (valueKind.isObject()) {
            equalsNode = ObjectEqualsNode.virtualizeComparison(expectedAlias, currentValue, graph(), tool);
        }
        if (equalsNode == null && !(expectedAlias instanceof VirtualObjectNode) && !(currentValue instanceof VirtualObjectNode)) {
            equalsNode = CompareNode.createCompareNode(EQ, expectedAlias, currentValue, tool.getConstantReflection(), NodeView.DEFAULT);
        }
        if (equalsNode == null) {
            tool.getDebug().log(DETAILED_LEVEL, "%s.virtualize() -> Expected and/or current values are virtual and the comparison can not be folded", this);
            return;
        }

        ValueNode newValueAlias = tool.getAlias(this.newValue);
        ValueNode fieldValue;
        if (equalsNode instanceof LogicConstantNode) {
            fieldValue = ((LogicConstantNode) equalsNode).getValue() ? newValue : currentValue;
        } else {
            if (currentValue instanceof VirtualObjectNode || newValueAlias instanceof VirtualObjectNode) {
                tool.getDebug().log(DETAILED_LEVEL, "%s.virtualize() -> Unknown outcome and current or new value is virtual", this);
                return;
            }
            fieldValue = ConditionalNode.create(equalsNode, newValueAlias, currentValue, NodeView.DEFAULT);
        }
        if (!tool.setVirtualEntry(obj, index, fieldValue, valueKind, constantOffset)) {
            tool.getDebug().log(DETAILED_LEVEL, "%s.virtualize() -> Could not set virtual entry", this);
            return;
        }
        tool.getDebug().log(DETAILED_LEVEL, "%s.virtualize() -> Success: virtualizing", this);
        tool.ensureAdded(equalsNode);
        tool.ensureAdded(fieldValue);
        finishVirtualize(tool, equalsNode, currentValue);
    }

    protected abstract void finishVirtualize(VirtualizerTool tool, LogicNode equalsNode, ValueNode currentValue);
}

/*
 * Copyright (c) 2015, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.pointsto.nodes;

import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.extended.RawLoadNode;
import org.graalvm.word.LocationIdentity;

import com.oracle.svm.util.UnsafePartitionKind;

import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaType;

@NodeInfo
public class UnsafePartitionLoadNode extends RawLoadNode {
    public static final NodeClass<UnsafePartitionLoadNode> TYPE = NodeClass.create(UnsafePartitionLoadNode.class);

    protected final UnsafePartitionKind partitionKind;
    protected final ResolvedJavaType partitionType;

    public UnsafePartitionLoadNode(ValueNode object, ValueNode offset, JavaKind accessKind, LocationIdentity locationIdentity, UnsafePartitionKind partitionKind,
                    ResolvedJavaType partitionType) {
        super(TYPE, object, offset, accessKind, locationIdentity);
        this.partitionKind = partitionKind;
        this.partitionType = partitionType;
    }

    public UnsafePartitionKind unsafePartitionKind() {
        return partitionKind;
    }

    public ResolvedJavaType partitionType() {
        return partitionType;
    }
}

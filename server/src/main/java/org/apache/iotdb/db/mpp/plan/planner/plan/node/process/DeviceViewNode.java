/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.mpp.plan.planner.plan.node.process;

import org.apache.iotdb.db.mpp.plan.planner.plan.node.PlanNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.PlanNodeId;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.PlanNodeType;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.PlanVisitor;
import org.apache.iotdb.db.mpp.plan.statement.component.OrderBy;
import org.apache.iotdb.tsfile.utils.ReadWriteIOUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * DeviceViewNode is responsible for constructing a device-based view of a set of series. And output
 * the result with specific order. The order could be 'order by device' or 'order by timestamp'
 *
 * <p>Each output from its children should have the same schema. That means, the columns should be
 * same between these TsBlocks. If the input TsBlock contains n columns, the device-based view will
 * contain n+1 columns where the new column is Device column.
 */
public class DeviceViewNode extends MultiChildNode {

  // The result output order, which could sort by device and time.
  // The size of this list is 2 and the first OrderBy in this list has higher priority.
  private final List<OrderBy> mergeOrders;

  // The size devices and children should be the same.
  private final List<String> devices = new ArrayList<>();

  // Device column and measurement columns in result output
  private final List<String> outputColumnNames;

  // e.g. [s1,s2,s3] is query, but [s1, s3] exists in device1, then device1 -> [1, 3], s1 is 1 but
  // not 0 because device is the first column
  private Map<String, List<Integer>> deviceToMeasurementIndexesMap;

  public DeviceViewNode(
      PlanNodeId id,
      List<OrderBy> mergeOrders,
      List<String> outputColumnNames,
      Map<String, List<Integer>> deviceToMeasurementIndexesMap) {
    super(id);
    this.mergeOrders = mergeOrders;
    this.outputColumnNames = outputColumnNames;
    this.deviceToMeasurementIndexesMap = deviceToMeasurementIndexesMap;
  }

  public DeviceViewNode(
      PlanNodeId id,
      List<OrderBy> mergeOrders,
      List<String> outputColumnNames,
      List<String> devices,
      Map<String, List<Integer>> deviceToMeasurementIndexesMap) {
    super(id);
    this.mergeOrders = mergeOrders;
    this.outputColumnNames = outputColumnNames;
    this.devices.addAll(devices);
    this.deviceToMeasurementIndexesMap = deviceToMeasurementIndexesMap;
  }

  public void addChildDeviceNode(String deviceName, PlanNode childNode) {
    this.devices.add(deviceName);
    this.children.add(childNode);
  }

  public List<String> getDevices() {
    return devices;
  }

  public Map<String, List<Integer>> getDeviceToMeasurementIndexesMap() {
    return deviceToMeasurementIndexesMap;
  }

  @Override
  public List<PlanNode> getChildren() {
    return children;
  }

  @Override
  public void addChild(PlanNode child) {
    this.children.add(child);
  }

  @Override
  public int allowedChildCount() {
    return CHILD_COUNT_NO_LIMIT;
  }

  @Override
  public PlanNode clone() {
    return new DeviceViewNode(
        getPlanNodeId(), mergeOrders, outputColumnNames, devices, deviceToMeasurementIndexesMap);
  }

  public List<OrderBy> getMergeOrders() {
    return mergeOrders;
  }

  @Override
  public List<String> getOutputColumnNames() {
    return outputColumnNames;
  }

  @Override
  public <R, C> R accept(PlanVisitor<R, C> visitor, C context) {
    return visitor.visitDeviceView(this, context);
  }

  @Override
  protected void serializeAttributes(ByteBuffer byteBuffer) {
    PlanNodeType.DEVICE_VIEW.serialize(byteBuffer);
    ReadWriteIOUtils.write(mergeOrders.get(0).ordinal(), byteBuffer);
    ReadWriteIOUtils.write(mergeOrders.get(1).ordinal(), byteBuffer);
    ReadWriteIOUtils.write(outputColumnNames.size(), byteBuffer);
    for (String column : outputColumnNames) {
      ReadWriteIOUtils.write(column, byteBuffer);
    }
    ReadWriteIOUtils.write(devices.size(), byteBuffer);
    for (String deviceName : devices) {
      ReadWriteIOUtils.write(deviceName, byteBuffer);
    }
    ReadWriteIOUtils.write(deviceToMeasurementIndexesMap.size(), byteBuffer);
    for (Map.Entry<String, List<Integer>> entry : deviceToMeasurementIndexesMap.entrySet()) {
      ReadWriteIOUtils.write(entry.getKey(), byteBuffer);
      ReadWriteIOUtils.write(entry.getValue().size(), byteBuffer);
      for (Integer index : entry.getValue()) {
        ReadWriteIOUtils.write(index, byteBuffer);
      }
    }
  }

  public static DeviceViewNode deserialize(ByteBuffer byteBuffer) {
    List<OrderBy> mergeOrders = new ArrayList<>();
    mergeOrders.add(OrderBy.values()[ReadWriteIOUtils.readInt(byteBuffer)]);
    mergeOrders.add(OrderBy.values()[ReadWriteIOUtils.readInt(byteBuffer)]);
    int columnSize = ReadWriteIOUtils.readInt(byteBuffer);
    List<String> outputColumnNames = new ArrayList<>();
    while (columnSize > 0) {
      outputColumnNames.add(ReadWriteIOUtils.readString(byteBuffer));
      columnSize--;
    }
    int devicesSize = ReadWriteIOUtils.readInt(byteBuffer);
    List<String> devices = new ArrayList<>();
    while (devicesSize > 0) {
      devices.add(ReadWriteIOUtils.readString(byteBuffer));
      devicesSize--;
    }
    int mapSize = ReadWriteIOUtils.readInt(byteBuffer);
    Map<String, List<Integer>> deviceToMeasurementIndexesMap = new HashMap<>(mapSize);
    while (mapSize > 0) {
      String deviceName = ReadWriteIOUtils.readString(byteBuffer);
      int listSize = ReadWriteIOUtils.readInt(byteBuffer);
      List<Integer> indexes = new ArrayList<>(listSize);
      while (listSize > 0) {
        indexes.add(ReadWriteIOUtils.readInt(byteBuffer));
        listSize--;
      }
      deviceToMeasurementIndexesMap.put(deviceName, indexes);
      mapSize--;
    }
    PlanNodeId planNodeId = PlanNodeId.deserialize(byteBuffer);
    return new DeviceViewNode(
        planNodeId, mergeOrders, outputColumnNames, devices, deviceToMeasurementIndexesMap);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    DeviceViewNode that = (DeviceViewNode) o;
    return mergeOrders.equals(that.mergeOrders)
        && devices.equals(that.devices)
        && children.equals(that.children)
        && outputColumnNames.equals(that.outputColumnNames)
        && deviceToMeasurementIndexesMap.equals(that.deviceToMeasurementIndexesMap);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        super.hashCode(),
        mergeOrders,
        devices,
        children,
        outputColumnNames,
        deviceToMeasurementIndexesMap);
  }

  @Override
  public String toString() {
    return "DeviceView-" + this.getPlanNodeId();
  }
}

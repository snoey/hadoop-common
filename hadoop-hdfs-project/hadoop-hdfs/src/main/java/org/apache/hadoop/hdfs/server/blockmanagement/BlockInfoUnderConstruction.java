/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.blockmanagement;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.BlockUCState;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.ReplicaState;
import org.apache.hadoop.hdfs.server.namenode.NameNode;

/**
 * Represents a block that is currently being constructed.<br>
 * This is usually the last block of a file opened for write or append.
 */
public class BlockInfoUnderConstruction extends BlockInfo {
  /** Block state. See {@link BlockUCState} */
  private BlockUCState blockUCState;

  /**
   * Block replicas as assigned when the block was allocated.
   * This defines the pipeline order.
   */
  private List<ReplicaUnderConstruction> replicas;

  /**
   * Index of the primary data node doing the recovery. Useful for log
   * messages.
   */
  private int primaryNodeIndex = -1;

  /**
   * The new generation stamp, which this block will have
   * after the recovery succeeds. Also used as a recovery id to identify
   * the right recovery if any of the abandoned recoveries re-appear.
   */
  private long blockRecoveryId = 0;

  /**
   * ReplicaUnderConstruction contains information about replicas while
   * they are under construction.
   * The GS, the length and the state of the replica is as reported by 
   * the data-node.
   * It is not guaranteed, but expected, that data-nodes actually have
   * corresponding replicas.
   */
  static class ReplicaUnderConstruction extends Block {
    private DatanodeDescriptor expectedLocation;
    private ReplicaState state;
    private boolean chosenAsPrimary;

    ReplicaUnderConstruction(Block block,
                             DatanodeDescriptor target,
                             ReplicaState state) {
      super(block);
      this.expectedLocation = target;
      this.state = state;
      this.chosenAsPrimary = false;
    }

    /**
     * Expected block replica location as assigned when the block was allocated.
     * This defines the pipeline order.
     * It is not guaranteed, but expected, that the data-node actually has
     * the replica.
     */
    DatanodeDescriptor getExpectedLocation() {
      return expectedLocation;
    }

    /**
     * Get replica state as reported by the data-node.
     */
    ReplicaState getState() {
      return state;
    }

    /**
     * Whether the replica was chosen for recovery.
     */
    boolean getChosenAsPrimary() {
      return chosenAsPrimary;
    }

    /**
     * Set replica state.
     */
    void setState(ReplicaState s) {
      state = s;
    }

    /**
     * Set whether this replica was chosen for recovery.
     */
    void setChosenAsPrimary(boolean chosenAsPrimary) {
      this.chosenAsPrimary = chosenAsPrimary;
    }

    /**
     * Is data-node the replica belongs to alive.
     */
    boolean isAlive() {
      return expectedLocation.isAlive;
    }

    @Override // Block
    public int hashCode() {
      return super.hashCode();
    }

    @Override // Block
    public boolean equals(Object obj) {
      // Sufficient to rely on super's implementation
      return (this == obj) || super.equals(obj);
    }

    @Override
    public String toString() {
      final StringBuilder b = new StringBuilder(50);
      appendStringTo(b);
      return b.toString();
    }
    
    @Override
    public void appendStringTo(StringBuilder sb) {
      sb.append("ReplicaUnderConstruction[")
        .append(expectedLocation)
        .append("|")
        .append(state)
        .append("]");
    }
  }

  /**
   * Create block and set its state to
   * {@link BlockUCState#UNDER_CONSTRUCTION}.
   */
  public BlockInfoUnderConstruction(Block blk, int replication) {
    this(blk, replication, BlockUCState.UNDER_CONSTRUCTION, null);
  }

  /**
   * Create a block that is currently being constructed.
   */
  public BlockInfoUnderConstruction(Block blk, int replication,
                             BlockUCState state,
                             DatanodeDescriptor[] targets) {
    super(blk, replication);
    assert getBlockUCState() != BlockUCState.COMPLETE :
      "BlockInfoUnderConstruction cannot be in COMPLETE state";
    this.blockUCState = state;
    setExpectedLocations(targets);
  }

  /**
   * Convert an under construction block to a complete block.
   * 
   * @return BlockInfo - a complete block.
   * @throws IOException if the state of the block 
   * (the generation stamp and the length) has not been committed by 
   * the client or it does not have at least a minimal number of replicas 
   * reported from data-nodes. 
   */
  BlockInfo convertToCompleteBlock() throws IOException {
    assert getBlockUCState() != BlockUCState.COMPLETE :
      "Trying to convert a COMPLETE block";
    return new BlockInfo(this);
  }

  /** Set expected locations */
  public void setExpectedLocations(DatanodeDescriptor[] targets) {
    int numLocations = targets == null ? 0 : targets.length;
    this.replicas = new ArrayList<ReplicaUnderConstruction>(numLocations);
    for(int i = 0; i < numLocations; i++)
      replicas.add(
        new ReplicaUnderConstruction(this, targets[i], ReplicaState.RBW));
  }

  /**
   * Create array of expected replica locations
   * (as has been assigned by chooseTargets()).
   */
  public DatanodeDescriptor[] getExpectedLocations() {
    int numLocations = replicas == null ? 0 : replicas.size();
    DatanodeDescriptor[] locations = new DatanodeDescriptor[numLocations];
    for(int i = 0; i < numLocations; i++)
      locations[i] = replicas.get(i).getExpectedLocation();
    return locations;
  }

  /** Get the number of expected locations */
  public int getNumExpectedLocations() {
    return replicas == null ? 0 : replicas.size();
  }

  /**
   * Return the state of the block under construction.
   * @see BlockUCState
   */
  @Override // BlockInfo
  public BlockUCState getBlockUCState() {
    return blockUCState;
  }

  void setBlockUCState(BlockUCState s) {
    blockUCState = s;
  }

  /** Get block recovery ID */
  public long getBlockRecoveryId() {
    return blockRecoveryId;
  }

  /**
   * Process the recorded replicas. When about to commit or finish the
   * pipeline recovery sort out bad replicas.
   * @param genStamp  The final generation stamp for the block.
   */
  public void setGenerationStampAndVerifyReplicas(long genStamp) {
    if (replicas == null)
      return;

    // Remove the replicas with wrong gen stamp.
    // The replica list is unchanged.
    for (ReplicaUnderConstruction r : replicas) {
      if (genStamp != r.getGenerationStamp()) {
        r.getExpectedLocation().removeBlock(this);
        NameNode.blockStateChangeLog.info("BLOCK* Removing stale replica "
            + "from location: " + r);
      }
    }

    // Set the generation stamp for the block.
    setGenerationStamp(genStamp);
  }

  /**
   * Commit block's length and generation stamp as reported by the client.
   * Set block state to {@link BlockUCState#COMMITTED}.
   * @param block - contains client reported block length and generation 
   * @throws IOException if block ids are inconsistent.
   */
  void commitBlock(Block block) throws IOException {
    if(getBlockId() != block.getBlockId())
      throw new IOException("Trying to commit inconsistent block: id = "
          + block.getBlockId() + ", expected id = " + getBlockId());
    blockUCState = BlockUCState.COMMITTED;
    this.set(getBlockId(), block.getNumBytes(), block.getGenerationStamp());
  }

  /**
   * Initialize lease recovery for this block.
   * Find the first alive data-node starting from the previous primary and
   * make it primary.
   */
  public void initializeBlockRecovery(long recoveryId) {
    setBlockUCState(BlockUCState.UNDER_RECOVERY);
    blockRecoveryId = recoveryId;
    if (replicas.size() == 0) {
      NameNode.blockStateChangeLog.warn("BLOCK*"
        + " BlockInfoUnderConstruction.initLeaseRecovery:"
        + " No blocks found, lease removed.");
    }
    boolean allLiveReplicasTriedAsPrimary = true;
    for (int i = 0; i < replicas.size(); i++) {
      // Check if all replicas have been tried or not.
      if (replicas.get(i).isAlive()) {
        allLiveReplicasTriedAsPrimary =
            (allLiveReplicasTriedAsPrimary && replicas.get(i).getChosenAsPrimary());
      }
    }
    if (allLiveReplicasTriedAsPrimary) {
      // Just set all the replicas to be chosen whether they are alive or not.
      for (int i = 0; i < replicas.size(); i++) {
        replicas.get(i).setChosenAsPrimary(false);
      }
    }
    long mostRecentLastUpdate = 0;
    ReplicaUnderConstruction primary = null;
    primaryNodeIndex = -1;
    for(int i = 0; i < replicas.size(); i++) {
      // Skip alive replicas which have been chosen for recovery.
      if (!(replicas.get(i).isAlive() && !replicas.get(i).getChosenAsPrimary())) {
        continue;
      }
      if (replicas.get(i).getExpectedLocation().getLastUpdate() > mostRecentLastUpdate) {
        primary = replicas.get(i);
        primaryNodeIndex = i;
        mostRecentLastUpdate = primary.getExpectedLocation().getLastUpdate();
      }
    }
    if (primary != null) {
      primary.getExpectedLocation().addBlockToBeRecovered(this);
      primary.setChosenAsPrimary(true);
      NameNode.blockStateChangeLog.info("BLOCK* " + this
        + " recovery started, primary=" + primary);
    }
  }

  void addReplicaIfNotPresent(DatanodeDescriptor dn,
                     Block block,
                     ReplicaState rState) {
    for (ReplicaUnderConstruction r : replicas) {
      if (r.getExpectedLocation() == dn) {
        // Record the gen stamp from the report
        r.setGenerationStamp(block.getGenerationStamp());
        return;
      }
    }
    replicas.add(new ReplicaUnderConstruction(block, dn, rState));
  }

  @Override // BlockInfo
  // BlockInfoUnderConstruction participates in maps the same way as BlockInfo
  public int hashCode() {
    return super.hashCode();
  }

  @Override // BlockInfo
  public boolean equals(Object obj) {
    // Sufficient to rely on super's implementation
    return (this == obj) || super.equals(obj);
  }

  @Override
  public String toString() {
    final StringBuilder b = new StringBuilder(100);
    appendStringTo(b);
    return b.toString();
  }

  @Override
  public void appendStringTo(StringBuilder sb) {
    super.appendStringTo(sb);
    appendUCParts(sb);
  }

  private void appendUCParts(StringBuilder sb) {
    sb.append("{blockUCState=").append(blockUCState)
      .append(", primaryNodeIndex=").append(primaryNodeIndex)
      .append(", replicas=[");
    Iterator<ReplicaUnderConstruction> iter = replicas.iterator();
    if (iter.hasNext()) {
      iter.next().appendStringTo(sb);
      while (iter.hasNext()) {
        sb.append(", ");
        iter.next().appendStringTo(sb);
      }
    }
    sb.append("]}");
  }
}

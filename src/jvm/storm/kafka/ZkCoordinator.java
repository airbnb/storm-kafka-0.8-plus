package storm.kafka;

import backtype.storm.task.IMetricsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import storm.kafka.trident.GlobalPartitionInformation;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.*;

public class ZkCoordinator implements PartitionCoordinator {
  public static final Logger LOG = LoggerFactory.getLogger(ZkCoordinator.class);

  SpoutConfig _spoutConfig;
  int _taskIndex;
  int _totalTasks;
  String _topologyInstanceId;
  Map<Partition, PartitionManager> _managers = new HashMap();
  List<PartitionManager> _cachedList = new ArrayList<PartitionManager>();
  Long _lastRefreshTime = null;
  int _refreshFreqMs;
  DynamicPartitionConnections _connections;
  DynamicBrokersReader _reader;
  ZkState _state;
  Map _stormConf;
  IMetricsContext _metricsContext;
  ZkHosts brokerConf;

  public ZkCoordinator(DynamicPartitionConnections connections, Map stormConf, SpoutConfig spoutConfig, ZkState state, int taskIndex, int totalTasks, String topologyInstanceId) {
    _spoutConfig = spoutConfig;
    _connections = connections;
    _taskIndex = taskIndex;
    _totalTasks = totalTasks;
    _topologyInstanceId = topologyInstanceId;
    _stormConf = stormConf;
    _state = state;

    brokerConf = (ZkHosts) spoutConfig.hosts;
    _refreshFreqMs = brokerConf.refreshFreqSecs * 1000;

    createReader();
  }

  private void createReader() {
    _reader = new DynamicBrokersReader(_stormConf, brokerConf.brokerZkStr, brokerConf.brokerZkPath, _spoutConfig.topic);
  }

  @Override
  public List<PartitionManager> getMyManagedPartitions() {
    if (_cachedList == null || _lastRefreshTime == null ||
        (System.currentTimeMillis() - _lastRefreshTime) > _refreshFreqMs) {
      refresh();
      _lastRefreshTime = System.currentTimeMillis();
    }
    return _cachedList;
  }

  @Override
  public void refresh() {
    try {
      LOG.info("Refreshing partition manager connections");
      GlobalPartitionInformation brokerInfo = _reader.getBrokerInfo();
      Set<Partition> mine = new HashSet();
      for (Partition partitionId : brokerInfo) {
        if (myOwnership(partitionId)) {
          mine.add(partitionId);
        }
      }

      Set<Partition> curr = _managers.keySet();
      Set<Partition> newPartitions = new HashSet<Partition>(mine);
      newPartitions.removeAll(curr);

      Set<Partition> deletedPartitions = new HashSet<Partition>(curr);
      deletedPartitions.removeAll(mine);

      LOG.info("Deleted partition managers: " + deletedPartitions.toString());

      for (Partition id : deletedPartitions) {
        PartitionManager man = _managers.remove(id);
        man.close();
      }
      LOG.info("New partition managers: " + newPartitions.toString());

      for (Partition id : newPartitions) {
        PartitionManager man = new PartitionManager(_connections, _topologyInstanceId, _state, _stormConf, _spoutConfig, id);
        _managers.put(id, man);
      }

    } catch (Exception e) {
      if (e instanceof ConnectException ||
          e instanceof SocketTimeoutException ||
          e instanceof IOException) {
        LOG.warn("Socket error", e);
        createReader();
        return;
      } else {
        throw new RuntimeException(e);
      }
    }
    _cachedList = new ArrayList<PartitionManager>(_managers.values());
    LOG.info("Finished refreshing");
  }

  @Override
  public PartitionManager getManager(Partition partition) {
    return _managers.get(partition);
  }

  private boolean myOwnership(Partition id) {
    return id.partition % _totalTasks == _taskIndex;
  }
}

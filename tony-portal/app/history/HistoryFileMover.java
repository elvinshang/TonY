package history;

import cache.CacheWrapper;
import com.linkedin.tony.Constants;
import com.linkedin.tony.TonyConfigurationKeys;
import com.linkedin.tony.util.ParserUtils;
import com.linkedin.tony.util.Utils;
import com.typesafe.config.Config;
import hadoop.Requirements;
import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import play.Logger;
import utils.ConfigUtils;


@Singleton
public class HistoryFileMover {
  private static final Logger.ALogger LOG = Logger.of(HistoryFileMover.class);

  private final FileSystem fs;
  private final Path intermediateDir;
  private final Path finishedDir;
  private final CacheWrapper cacheWrapper;

  @Inject
  public HistoryFileMover(Config appConf, Requirements requirements, CacheWrapper cacheWrapper) {
    fs = requirements.getFileSystem();
    intermediateDir = requirements.getIntermediateDir();
    finishedDir = requirements.getFinishedDir();
    this.cacheWrapper = cacheWrapper;

    ScheduledExecutorService scheduledThreadPool = Executors.newScheduledThreadPool(1);
    long moverIntervalMs = ConfigUtils.fetchIntConfigIfExists(appConf,
        TonyConfigurationKeys.TONY_HISTORY_MOVER_INTERVAL_MS,
        TonyConfigurationKeys.DEFAULT_TONY_HISTORY_MOVER_INTERVAL_MS);
    String finishedDirTimeZone = ConfigUtils.fetchConfigIfExists(appConf,
        TonyConfigurationKeys.TONY_HISTORY_FINISHED_DIR_TIMEZONE,
        TonyConfigurationKeys.DEFAULT_TONY_HISTORY_FINISHED_DIR_TIMEZONE);

    // Throws DateTimeException or ZoneRulesException given wrong TimeZone format.
    ZoneId zoneId = ZoneId.of(finishedDirTimeZone);

    LOG.info("Starting background history file mover thread, will run every " + moverIntervalMs + " milliseconds.");
    scheduledThreadPool.scheduleAtFixedRate(() -> {
      FileStatus[] jobDirs = null;
      try {
        jobDirs = fs.listStatus(intermediateDir);
      } catch (IOException e) {
        LOG.error("Failed to list files in " + intermediateDir, e);
      }
      if (jobDirs != null) {
        try {
          moveIntermediateToFinished(fs, jobDirs, zoneId);
        } catch (Exception e) {
          LOG.error("Encountered exception while moving history directories", e);
        }
      }
    }, 0, moverIntervalMs, TimeUnit.MILLISECONDS);

    //Compares the Yarn appIDs of Killed apps to those in the
    // intermediate directory and calls a function to rename them

    scheduledThreadPool.scheduleAtFixedRate(() -> {
      FileStatus[] intermedDir = null;
      List<FileStatus> killedDirsList = new ArrayList<>();

      try {
        intermedDir = fs.listStatus(intermediateDir);
      } catch (IOException e) {
        LOG.error("Failed to list files in " + intermediateDir , e);
      }

      if (intermedDir != null) {

        try {
          for (String k : getKilledApps()) {
            for (FileStatus jobDr : intermedDir) {
              if (jobDr.toString().contains(k)) {
                killedDirsList.add(jobDr);
              }
            }
          }
        } catch (IOException e) {
          e.printStackTrace();
        } catch (YarnException e) {
          e.printStackTrace();
        }

        LOG.info("Number of Killed Apps in Intermediate Directory: " + killedDirsList.size());

        try {
          renameKilledApps(fs, killedDirsList);
          LOG.info("Renamed Killed Apps in Intermediate Directory");
        } catch (Exception e) {
          LOG.error("Encountered exception renaming KILLED apps", e);
        }
      }

    }, 0, moverIntervalMs, TimeUnit.MILLISECONDS);

  }

  private void moveIntermediateToFinished(FileSystem fs, FileStatus[] jobDirs, ZoneId zoneId) {
    for (FileStatus jobDir : jobDirs) {
      cacheWrapper.updateCaches(jobDir.getPath());
      String jhistFilePath = ParserUtils.getJhistFilePath(fs, jobDir.getPath());
      if (jhistFilePath == null || jobInProgress(jhistFilePath)) {
        continue;
      }

      Path source = new Path(jhistFilePath).getParent();
      StringBuilder destString = new StringBuilder(finishedDir.toString());
      Date endDate = new Date(ParserUtils.getCompletedTimeFromJhistFileName(jhistFilePath));
      destString.append(Path.SEPARATOR).append(ParserUtils.getYearMonthDayDirectory(endDate, zoneId));
      if (fs.getScheme().equals("file")) {
        // Local filesystem will copy contents of source dir to dest dir, so we have to append the source dir name
        // to the dest dir to compensate.
        destString.append(Path.SEPARATOR).append(source.getName());
      }
      Utils.createDirIfNotExists(fs, new Path(destString.toString()), Constants.PERM770);

      Path dest = new Path(destString.toString());
      LOG.info("Moving " + source + " to " + dest);
      try {
        fs.rename(source, dest);
      } catch (IOException e) {
        LOG.error("Failed to move files from intermediate to finished", e);
      }
    }
  }

  private boolean jobInProgress(String jhistFileName) {
    return !jhistFileName.endsWith(Constants.HISTFILE_SUFFIX);
  }

  // This function queries and returns Yarn for the App IDs of apps with YarnApplicationState.KILLED

  private List<String> getKilledApps() throws IOException, YarnException {
    List<String> totalKilledAppIds = new ArrayList<String>();
    long currentTimestamp = System.currentTimeMillis();

    //set timeDelta for 36 hours
    long timeDelta = 36 * 60 * 60 * 1000;
    YarnConfiguration yarnConf = new YarnConfiguration();
    if (System.getenv(Constants.HADOOP_CONF_DIR) != null) {
      yarnConf.addResource(new Path(System.getenv(Constants.HADOOP_CONF_DIR) +
          File.separatorChar + Constants.CORE_SITE_CONF));
      yarnConf.addResource(new Path(System.getenv(Constants.HADOOP_CONF_DIR) +
          File.separatorChar + Constants.YARN_SITE_CONF));
    }
    YarnClient yarnClient = YarnClient.createYarnClient();
    yarnClient.init(yarnConf);
    yarnClient.start();
    List<ApplicationReport> totalKilledApps = yarnClient.getApplications(EnumSet.of(YarnApplicationState.KILLED));

    // Get apps within a specified time period. This prevents the History File mover from iterating through
    //previously checked killed app IDs upon every iteration.

    List<String> timeDeltaKilledIds = totalKilledApps
        .stream()
        .filter(x -> x.getStartTime() > (currentTimestamp - timeDelta))
        .map(x -> x.getApplicationId().toString())
        .collect(Collectors.toList());

    LOG.info("Number Killed Yarn Apps within timeDelta: " + timeDeltaKilledIds.size());
    return timeDeltaKilledIds;
  }

  private void renameKilledApps(FileSystem fs, List<FileStatus> jobDirs) {

    for (FileStatus jobDir : jobDirs) {
      String jhistFilePath = ParserUtils.getJhistFilePath(fs, jobDir.getPath());

      if (jhistFilePath.endsWith(".jhist.inprogress")) {
        {
          //new file name will need an end time, set it to current time
          long currentTimestamp = System.currentTimeMillis();
          Path sourcePath = new Path(jhistFilePath);
          String jhistFileName = jhistFilePath.substring(jhistFilePath.lastIndexOf('/') + 1);

          //Section of filename that will be replaced --> username.jhist.inprogress
          String oldJhistSubstring = jhistFileName.substring(jhistFileName.lastIndexOf('-') + 1);
          String username = oldJhistSubstring.split("\\.")[0];
          String newJhistSubstring = currentTimestamp + "-" + username + "-KILLED.jhist";
          String killedJhistFilePath = jhistFilePath.replace(oldJhistSubstring, newJhistSubstring);
          Path killedPath = new Path(killedJhistFilePath);
          try {
            fs.rename(sourcePath, killedPath);
          } catch (IOException e) {
            LOG.error("Failed to rename KILLED apps", e);
          }
        }
      }
    }
  }
}

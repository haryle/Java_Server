package handlers;

import utility.FileMetadata;

import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

public class RemoveEntryRunnable implements Runnable {
    private final Logger logger;
    private final FileMetadata popData;
    private final ConcurrentMap<String, ConcurrentMap<String, ConcurrentMap<String, String>>> archive;

    public RemoveEntryRunnable(
            FileMetadata metadata,
            ConcurrentMap<String, ConcurrentMap<String, ConcurrentMap<String, String>>> archive) {
        this.popData = metadata;
        this.archive = archive;
        logger = Logger.getLogger(this.getClass().getName());
    }

    @Override
    public void run() {
        String popIP = popData.getRemoteIP();
        String popFileName = popData.getFileName();
        String popTS = popData.getTimestamp();
        logger.info("Begin removing entry at TS: " + popTS + " : " + popIP + "/" + popFileName);
        if (archive.containsKey(popIP) && archive.get(popIP).containsKey(popFileName)) {
            String archiveTS = archive.get(popIP).get(popFileName).get("Timestamp");
            // If old data hasn't been updated since -> Remove
            if (popTS.equals(archiveTS)) {
                logger.info("Success: Remove archive entry at: " + popIP + "/" + popFileName);
                archive.get(popIP).remove(popFileName);
            } else
                logger.info("Failed: Entry was updated: " + archiveTS + " : " + popIP + "/" + popFileName);
        } else
            logger.info("Failed: Entry was already removed: " + popIP + "/" + popFileName);
    }
}

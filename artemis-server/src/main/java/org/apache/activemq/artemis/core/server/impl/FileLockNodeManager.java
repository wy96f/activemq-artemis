/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.core.server.impl;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

import org.apache.activemq.artemis.api.core.ActiveMQIllegalStateException;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.ActivateCallback;
import org.apache.activemq.artemis.core.server.ActiveMQLockAcquisitionTimeoutException;
import org.apache.activemq.artemis.core.server.ActiveMQServerLogger;
import org.apache.activemq.artemis.core.server.NodeManager;
import org.apache.activemq.artemis.utils.UUID;
import org.jboss.logging.Logger;

public class FileLockNodeManager extends NodeManager {

   private static final Logger logger = Logger.getLogger(FileLockNodeManager.class);

   private static final int STATE_LOCK_POS = 0;

   private static final int LIVE_LOCK_POS = 1;

   private static final int BACKUP_LOCK_POS = 2;

   private static final long LOCK_LENGTH = 1;

   private static final byte LIVE = 'L';

   private static final byte FAILINGBACK = 'F';

   private static final byte PAUSED = 'P';

   private static final byte NOT_STARTED = 'N';

   private static final long LOCK_ACCESS_FAILURE_WAIT_TIME = 2000;

   private FileLock liveLock;

   private FileLock backupLock;

   private final FileChannel[] lockChannels = new FileChannel[3];

   protected long lockAcquisitionTimeout = -1;

   protected boolean interrupted = false;

   public FileLockNodeManager(final File directory, boolean replicatedBackup) {
      super(replicatedBackup, directory);
   }

   public FileLockNodeManager(final File directory, boolean replicatedBackup, long lockAcquisitionTimeout) {
      super(replicatedBackup, directory);

      this.lockAcquisitionTimeout = lockAcquisitionTimeout;
   }

   @Override
   public synchronized void start() throws Exception {
      if (isStarted()) {
         return;
      }
      if (!replicatedBackup) {
         setUpServerLockFile();
      }

      super.start();
   }

   @Override
   protected synchronized void setUpServerLockFile() throws IOException {
      super.setUpServerLockFile();

      for (int i = 0; i < 3; i++) {
         if (lockChannels[i] != null && lockChannels[i].isOpen()) {
            continue;
         }
         File fileLock = newFile("serverlock." + i);
         if (!fileLock.exists()) {
            fileLock.createNewFile();
         }
         RandomAccessFile randomFileLock = new RandomAccessFile(fileLock, "rw");
         lockChannels[i] = randomFileLock.getChannel();
      }
   }

   @Override
   public synchronized void stop() throws Exception {
      for (FileChannel channel : lockChannels) {
         if (channel != null && channel.isOpen()) {
            try {
               channel.close();
            } catch (Throwable e) {
               // I do not want to interrupt a shutdown. If anything is wrong here, just log it
               // it could be a critical error or something like that throwing the system down
               logger.warn(e.getMessage(), e);
            }
         }
      }

      super.stop();
   }

   @Override
   public boolean isAwaitingFailback() throws Exception {
      return getState() == FileLockNodeManager.FAILINGBACK;
   }

   @Override
   public boolean isBackupLive() throws Exception {
      FileLock liveAttemptLock;
      liveAttemptLock = tryLock(FileLockNodeManager.LIVE_LOCK_POS);
      if (liveAttemptLock == null) {
         return true;
      } else {
         liveAttemptLock.release();
         return false;
      }
   }

   public boolean isLiveLocked() {
      return liveLock != null;
   }

   @Override
   public void interrupt() {
      interrupted = true;
   }

   @Override
   public final void releaseBackup() throws Exception {
      if (backupLock != null) {
         backupLock.release();
         backupLock = null;
      }
   }

   @Override
   public void awaitLiveNode() throws Exception {
      logger.debug("awaiting live node...");
      do {
         byte state = getState();
         while (state == FileLockNodeManager.NOT_STARTED || state == FIRST_TIME_START) {
            logger.debug("awaiting live node startup state='" + state + "'");
            Thread.sleep(2000);
            state = getState();
         }

         liveLock = lock(FileLockNodeManager.LIVE_LOCK_POS);
         if (interrupted) {
            interrupted = false;
            throw new InterruptedException("Lock was interrupted");
         }
         state = getState();
         if (state == FileLockNodeManager.PAUSED) {
            liveLock.release();
            logger.debug("awaiting live node restarting");
            Thread.sleep(2000);
         } else if (state == FileLockNodeManager.FAILINGBACK) {
            liveLock.release();
            logger.debug("awaiting live node failing back");
            Thread.sleep(2000);
         } else if (state == FileLockNodeManager.LIVE) {
            logger.debug("acquired live node lock state = " + (char) state);
            break;
         }
      }
      while (true);
   }

   @Override
   public void startBackup() throws Exception {
      assert !replicatedBackup; // should not be called if this is a replicating backup
      ActiveMQServerLogger.LOGGER.waitingToBecomeBackup();

      backupLock = lock(FileLockNodeManager.BACKUP_LOCK_POS);
      ActiveMQServerLogger.LOGGER.gotBackupLock();
      if (getUUID() == null)
         readNodeId();
   }

   @Override
   public ActivateCallback startLiveNode() throws Exception {
      setFailingBack();

      String timeoutMessage = lockAcquisitionTimeout == -1 ? "indefinitely" : lockAcquisitionTimeout + " milliseconds";

      ActiveMQServerLogger.LOGGER.waitingToObtainLiveLock(timeoutMessage);

      liveLock = lock(FileLockNodeManager.LIVE_LOCK_POS);

      ActiveMQServerLogger.LOGGER.obtainedLiveLock();

      return new CleaningActivateCallback() {
         @Override
         public void activationComplete() {
            try {
               setLive();
            } catch (Exception e) {
               ActiveMQServerLogger.LOGGER.warn(e.getMessage(), e);
            }
         }
      };
   }

   @Override
   public void pauseLiveServer() throws Exception {
      setPaused();
      if (liveLock != null) {
         liveLock.release();
      }
   }

   @Override
   public void crashLiveServer() throws Exception {
      if (liveLock != null) {
         liveLock.release();
         liveLock = null;
      }
   }

   @Override
   public void awaitLiveStatus() throws Exception {
      while (getState() != LIVE) {
         Thread.sleep(2000);
      }
   }

   private void setLive() throws Exception {
      writeFileLockStatus(FileLockNodeManager.LIVE);
   }

   private void setFailingBack() throws Exception {
      writeFileLockStatus(FAILINGBACK);
   }

   private void setPaused() throws Exception {
      writeFileLockStatus(PAUSED);
   }

   /**
    * @param status
    * @throws IOException
    */
   private void writeFileLockStatus(byte status) throws Exception {
      if (replicatedBackup && channel == null)
         return;
      logger.debug("writing status: " + status);
      ByteBuffer bb = ByteBuffer.allocateDirect(1);
      bb.put(status);
      bb.position(0);
      if (!channel.isOpen()) {
         setUpServerLockFile();
      }
      FileLock lock = null;
      try {
         lock = lock(STATE_LOCK_POS);
         channel.write(bb, 0);
         channel.force(true);
      } finally {
         if (lock != null) {
            lock.release();
         }
      }
   }

   private byte getState() throws Exception {
      byte result;
      logger.debug("getting state...");
      ByteBuffer bb = ByteBuffer.allocateDirect(1);
      int read;
      FileLock lock = null;
      try {
         lock = lock(STATE_LOCK_POS);
         read = channel.read(bb, 0);
         if (read <= 0) {
            result = FileLockNodeManager.NOT_STARTED;
         } else {
            result = bb.get(0);
         }
      } finally {
         if (lock != null) {
            lock.release();
         }
      }

      logger.debug("state: " + result);

      return result;
   }

   @Override
   public final SimpleString readNodeId() throws ActiveMQIllegalStateException, IOException {
      ByteBuffer id = ByteBuffer.allocateDirect(16);
      int read = channel.read(id, 3);
      if (read != 16) {
         throw new ActiveMQIllegalStateException("live server did not write id to file");
      }
      byte[] bytes = new byte[16];
      id.position(0);
      id.get(bytes);
      setUUID(new UUID(UUID.TYPE_TIME_BASED, bytes));
      return getNodeId();
   }

   protected FileLock tryLock(final int lockPos) throws IOException {
      try {
         logger.debug("trying to lock position: " + lockPos);
         FileLock lock = lockChannels[lockPos].tryLock();
         if (lock != null) {
            logger.debug("locked position: " + lockPos);
         } else {
            logger.debug("failed to lock position: " + lockPos);
         }
         return lock;
      } catch (java.nio.channels.OverlappingFileLockException ex) {
         // This just means that another object on the same JVM is holding the lock
         return null;
      }
   }

   protected FileLock lock(final int lockPosition) throws Exception {
      long start = System.currentTimeMillis();
      boolean isRecurringFailure = false;

      while (!interrupted) {
         try {
            FileLock lock = tryLock(lockPosition);
            isRecurringFailure = false;

            if (lock == null) {
               try {
                  Thread.sleep(500);
               } catch (InterruptedException e) {
                  return null;
               }

               if (lockAcquisitionTimeout != -1 && (System.currentTimeMillis() - start) > lockAcquisitionTimeout) {
                  throw new ActiveMQLockAcquisitionTimeoutException("timed out waiting for lock");
               }
            } else {
               return lock;
            }
         } catch (IOException e) {
            // IOException during trylock() may be a temporary issue, e.g. NFS volume not being accessible

            logger.log(isRecurringFailure ? Logger.Level.DEBUG : Logger.Level.WARN,
                    "Failure when accessing a lock file", e);
            isRecurringFailure = true;

            long waitTime = LOCK_ACCESS_FAILURE_WAIT_TIME;
            if (lockAcquisitionTimeout != -1) {
               final long remainingTime = lockAcquisitionTimeout - (System.currentTimeMillis() - start);
               if (remainingTime <= 0) {
                  throw new ActiveMQLockAcquisitionTimeoutException("timed out waiting for lock");
               }
               waitTime = Math.min(waitTime, remainingTime);
            }

            try {
               Thread.sleep(waitTime);
            } catch (InterruptedException interrupt) {
               return null;
            }
         }
      }

      // presumed interrupted
      return null;
   }

}

/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 * 
 * DrFTPD is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * 
 * DrFTPD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with DrFTPD; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package net.sf.drftpd.event.listeners;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Properties;

import net.sf.drftpd.event.Event;
import net.sf.drftpd.event.FtpListener;
import net.sf.drftpd.master.ConnectionManager;
import net.sf.drftpd.master.config.FtpConfig;

import org.apache.log4j.Logger;
import org.drftpd.mirroring.ArchiveHandler;
import org.drftpd.mirroring.ArchiveType;
import org.drftpd.sections.SectionInterface;

/**
 * @author zubov
 * @version $Id: Archive.java,v 1.25 2004/05/12 00:45:05 mog Exp $
 */

public class Archive implements FtpListener, Runnable {
	private Properties _props;

	private static final Logger logger = Logger.getLogger(Archive.class);

	public static Logger getLogger() {
		return logger;
	}
	private HashMap _archiveTypes;
	private ConnectionManager _cm;
	private long _cycleTime;
	private ArrayList _exemptList = new ArrayList();
	private boolean _isStopped = false;
	private Thread thread = null;

	public Archive() {
		logger.info("Archive plugin loaded successfully");
	}
	
	public Properties getProperties() {
		return _props;
	}

	public void actionPerformed(Event event) {
		if (event.getCommand().equals("RELOAD")) {
			reload();
			return;
		}
	}

	/**
	 * @param lrf
	 * Returns true if lrf.getPath() is excluded
	 */
	public boolean checkExclude(SectionInterface section) {
		return _exemptList.contains(section.getName());
	}

	/**
	 * @return the correct ArchiveType for the @section - if the ArchiveType is still being used, it will return null
	 */
	public ArchiveType getArchiveType(SectionInterface section) {
		ArchiveType archiveType = (ArchiveType) _archiveTypes.get(section);
		if (archiveType == null)
			throw new IllegalStateException(
				"Could not find an archive type for "
					+ section.getName()
					+ ", check you make sure default.archiveType is defined in archive.conf");
		if (archiveType.getDirectory() != null) {
			return null;
		}
		return archiveType;
	}

	/**
	 * Returns the ConnectionManager
	 */
	public ConnectionManager getConnectionManager() {
		return _cm;
	}

	/**
	 * Returns the getCycleTime setting
	 */
	public long getCycleTime() {
		return _cycleTime;
	}

	public void init(ConnectionManager connectionManager) {
		_cm = connectionManager;
		_cm.loadJobManager();
		reload();
		startArchive();
	}

	private boolean isStopped() {
		return _isStopped;
	}

	private void reload() {
		_props = new Properties();
		try {
			_props.load(new FileInputStream("conf/archive.conf"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		_cycleTime =
			60000 * Long.parseLong(FtpConfig.getProperty(_props, "cycleTime"));
		_exemptList = new ArrayList();
		for (int i = 1;; i++) {
			String path = _props.getProperty("exclude." + i);
			if (path == null)
				break;
			_exemptList.add(path);
		}
		_archiveTypes = new HashMap();
		for (Iterator iter =
			getConnectionManager().getSectionManager().getSections().iterator();
			iter.hasNext();
			) {
			SectionInterface section = (SectionInterface) iter.next();
			if (checkExclude(section))
				// don't have to build an archiveType for sections that won't be archived
				continue;
			ArchiveType archiveType = null;
			try {
				archiveType =
					(ArchiveType) Class
						.forName(
							"org.drftpd.mirroring.archivetypes."
								+ FtpConfig.getProperty(
									_props,
									section.getName() + ".archiveType"))
						.newInstance();
			} catch (NullPointerException e) {
				try {
					archiveType =
						(ArchiveType) Class
							.forName(
								"org.drftpd.mirroring.archivetypes."
									+ FtpConfig.getProperty(
										_props,
										"default.archiveType"))
							.newInstance();
				} catch (Exception e1) {
					logger.info(
						"Unable to load ArchiveType for " + section.getName(),
						e);
				}
			} catch (Exception e) {
				logger.info(
					"Unable to load ArchiveType for " + section.getName(),
					e);
			}
			archiveType.init(this, section);
			_archiveTypes.put(section, archiveType);
			logger.debug("added archiveType for section " + section.getName());
		}
	}

	public void run() {
		ArrayList archiveHandlers = new ArrayList();
		while (true) {
			if (isStopped()) {
				logger.debug("Stopping ArchiveStarter thread");
				return;
			}
			for (Iterator iter = archiveHandlers.iterator(); iter.hasNext();) {
				ArchiveHandler archiveHandler = (ArchiveHandler) iter.next();
				if (!archiveHandler.isAlive()) {
					archiveHandler.getArchiveType().setDirectory(null);
					archiveHandler.getArchiveType().setRSlaves(null);
					iter.remove();
				}
			}
			Collection sectionsToCheck =
				getConnectionManager().getSectionManager().getSections();
			for (Iterator iter = sectionsToCheck.iterator(); iter.hasNext();) {
				SectionInterface section = (SectionInterface) iter.next();
				if (checkExclude(section))
					continue;
				ArchiveType archiveType = getArchiveType(section);
				if (archiveType == null) // archiveType was not done with it's current send, cannot process another
					continue;
				ArchiveHandler archiveHandler = new ArchiveHandler(archiveType);
				archiveHandlers.add(archiveHandler);
				archiveHandler.start();
			}
			try {
				Thread.sleep(_cycleTime);
			} catch (InterruptedException e) {
			}
		}
	}

	public void startArchive() {
		if (thread != null) {
			stopArchive();
			thread.interrupt();
			while (thread.isAlive()) {
				Thread.yield();
			}
		}
		_isStopped = false;
		thread = new Thread(this, "ArchiveStarter");
		thread.start();
	}

	public void stopArchive() {
		_isStopped = true;
	}

	public void unload() {
		stopArchive();
	}

}

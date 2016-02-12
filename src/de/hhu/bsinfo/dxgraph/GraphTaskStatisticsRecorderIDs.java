package de.hhu.bsinfo.dxgraph;

import de.hhu.bsinfo.dxram.stats.StatisticsRecorder;

/**
 * Metadata needed for registering and recording statistics in GraphTask.
 * @author Stefan Nothaas <stefan.nothaas@hhu.de> 12.02.16
 *
 */
public class GraphTaskStatisticsRecorderIDs {
	public int m_id = StatisticsRecorder.INVALID_ID;
	public Operations m_operations = new Operations();
	
	public static class Operations {
		public static final String MS_EXECUTE = "Execute";
		
		public int m_execute = StatisticsRecorder.Operation.INVALID_ID;
	}
}

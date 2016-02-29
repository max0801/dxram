package de.hhu.bsinfo.dxcompute.stats;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;

public class PrintStatisticsToFileTask extends PrintStatisticsTask {

	private String m_path = null;
	
	public PrintStatisticsToFileTask(final String p_path)
	{
		m_path = p_path;
	}
	
	@Override
	protected boolean execute() 
	{
		File file = new File(m_path);
		if (file.exists())
		{
			file.delete();
			try {
				file.createNewFile();
			} catch (IOException e) {
				m_loggerService.error(getClass(), "Creating output file " + m_path + " for statistics failed", e);
				return false;
			}
		}
		
		PrintStream out;
		try {
			out = new PrintStream(file);
		} catch (FileNotFoundException e) {
			m_loggerService.error(getClass(), "Creating print stream for statistics failed", e);
			return false;
		}
		printStatisticsToOutput(out);
		
		out.close();
		
		return true;
	}
}
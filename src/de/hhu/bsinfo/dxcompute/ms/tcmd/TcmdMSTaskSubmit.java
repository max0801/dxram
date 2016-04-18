
package de.hhu.bsinfo.dxcompute.ms.tcmd;

import de.hhu.bsinfo.dxram.chunk.ChunkService;
import de.hhu.bsinfo.dxram.data.ChunkID;
import de.hhu.bsinfo.dxram.term.AbstractTerminalCommand;
import de.hhu.bsinfo.utils.args.ArgumentList;
import de.hhu.bsinfo.utils.args.ArgumentList.Argument;

public class TcmdMSTaskSubmit extends AbstractTerminalCommand {
	private static final Argument MS_ARG_SIZE = new Argument("size", null, false, "Size of the chunk to create");
	private static final Argument MS_ARG_NODEID =
			new Argument("nodeid", null, false, "Node id of the peer to create the chunk on");

	@Override
	public String getName() {
		return "chunkcreate";
	}

	@Override
	public String getDescription() {
		return "Create a chunk on a remote node";
	}

	@Override
	public void registerArguments(final ArgumentList p_arguments) {
		p_arguments.setArgument(MS_ARG_SIZE);
		p_arguments.setArgument(MS_ARG_NODEID);
	}

	@Override
	public boolean execute(ArgumentList p_arguments) {
		Integer size = p_arguments.getArgumentValue(MS_ARG_SIZE, Integer.class);
		Short nodeID = p_arguments.getArgumentValue(MS_ARG_NODEID, Short.class);

		ChunkService chunkService = getTerminalDelegate().getDXRAMService(ChunkService.class);

		if (size == null) {
			System.out.println("Missing size parameter.");
			return false;
		}

		long[] chunkIDs = null;

		chunkIDs = chunkService.create(nodeID, size);

		if (chunkIDs != null) {

			System.out.println("Created chunk of size " + size + ": 0x" + ChunkID.toHexString(chunkIDs[0]));
		} else {
			System.out.println("Creating chunk failed.");
		}

		return true;
	}
}
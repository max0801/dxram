{
	"m_numRequiredSlaves": 0,
	"m_name": "ChunkCreateTest",
	"m_tasks": [
		{
			"m_task": "de.hhu.bsinfo.dxcompute.bench.CheckChunkMemRequiredSizeTask",
			"m_minRequiredSize": {
		        "m_value": 2,
        		"m_unit": "gb"
			},
			"m_minRequiredFree": {
		        "m_value": 1800,
        		"m_unit": "mb"
			}
		},
		{
			"m_switchCases": [
				{
					"m_caseValue": -1,
					"m_case": {
						"m_tasks": [
							{
							  	"m_abortMsg": "Minimum required key value store size: 1 GB"
							}
						]
					}
				},
				{
					"m_caseValue": -2,
					"m_case": {
						"m_tasks": [
							{
						  		"m_abortMsg": "Not enough free key value store memory, min required: 800 MB"
							}
						]
					}
				}
			]
		},
		{
		  	"m_task": "de.hhu.bsinfo.dxcompute.bench.ChunkCreateTask",
			"m_numThreads": 4,
			"m_chunkCount": 100000,
			"m_chunkBatch": 10,
			"m_chunkSizeBytesBegin": {
		        "m_value": 16,
        		"m_unit": "b"
			},
			"m_chunkSizeBytesEnd": {
		        "m_value": 1024,
        		"m_unit": "b"
			},
			"m_pattern": 0
		},
		{
			"m_task": "de.hhu.bsinfo.dxcompute.ms.tasks.PrintMemoryStatusToConsoleTask"
		}
	]
}

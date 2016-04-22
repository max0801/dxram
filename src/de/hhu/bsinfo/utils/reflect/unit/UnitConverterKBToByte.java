
package de.hhu.bsinfo.utils.reflect.unit;

/**
 * Convert a kilobyte units value to byte units.
 * @author Stefan Nothaas <stefan.nothaas@hhu.de> 26.01.16
 */
public class UnitConverterKBToByte implements UnitConverter {
	@Override
	public String getUnitIdentifier() {
		return "kb2b";
	}

	@Override
	public Object convert(final Object p_value) {
		if (p_value instanceof Byte) {
			return ((Byte) p_value) * 1024;
		} else if (p_value instanceof Short) {
			return ((Short) p_value) * 1024;
		} else if (p_value instanceof Integer) {
			return ((Integer) p_value) * 1024;
		} else if (p_value instanceof Long) {
			return ((Long) p_value) * 1024;
		} else {
			return p_value;
		}
	}
}

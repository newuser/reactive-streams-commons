package reactivestreams.commons.flow;

import java.util.Iterator;

/**
 * A component that will emit events to N downstreams.
 */
public interface MultiProducer {

	/**
	 * @return the connected data receivers
	 */
	Iterator<?> downstreams();

	/**
	 * @return the number of downstream receivers
	 */
	long downstreamCount();

}

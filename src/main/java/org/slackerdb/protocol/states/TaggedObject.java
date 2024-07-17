package org.slackerdb.protocol.states;

import org.slackerdb.protocol.context.Tag;

import java.util.List;

/**
 * Object containing tags (key-value pairs)
 */
public interface TaggedObject {
    /**
     * Retrieve the list of tags
     *
     * @return
     */
    List<Tag> getTag();
}

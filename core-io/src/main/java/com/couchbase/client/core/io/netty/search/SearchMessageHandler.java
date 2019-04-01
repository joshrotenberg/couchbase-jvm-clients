/*
 * Copyright (c) 2019 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.core.io.netty.search;

import com.couchbase.client.core.endpoint.EndpointContext;
import com.couchbase.client.core.io.netty.chunk.ChunkedMessageHandler;
import com.couchbase.client.core.msg.search.*;

public class SearchMessageHandler
        extends ChunkedMessageHandler<SearchChunkHeader, SearchChunkRow, SearchChunkTrailer, SearchResponse, SearchRequest> {

    public SearchMessageHandler(EndpointContext endpointContext) {
        super(endpointContext, new SearchChunkResponseParser());
    }

}


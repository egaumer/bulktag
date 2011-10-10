package co.diji.rest;

import org.elasticsearch.ElasticSearchParseException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.WriteConsistencyLevel;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.support.replication.ReplicationType;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.action.RequestBuilder;
import org.elasticsearch.common.compress.lzf.LZF;
import org.elasticsearch.common.compress.lzf.LZFChunk;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.BytesStreamInput;
import org.elasticsearch.common.io.stream.CachedStreamInput;
import org.elasticsearch.common.io.stream.LZFStreamInput;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.*;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.rest.*;
import org.elasticsearch.rest.action.support.RestActions;
import org.elasticsearch.rest.action.support.RestXContentBuilder;
import org.elasticsearch.search.lookup.SourceLookup;

import java.io.IOException;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;

import static org.elasticsearch.rest.RestStatus.CREATED;
import static org.elasticsearch.rest.RestStatus.NOT_FOUND;
import static org.elasticsearch.rest.RestStatus.OK;
import static org.elasticsearch.rest.action.support.RestXContentBuilder.restContentBuilder;

import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.client.Requests;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.bulk.BulkItemResponse;
import static org.elasticsearch.index.query.FilterBuilders.*;
import static org.elasticsearch.index.query.QueryBuilders.*;

import org.elasticsearch.search.lookup.SourceLookup;

public class BulkTagRestAction extends BaseRestHandler {

    @Inject
    public BulkTagRestAction(Settings settings, Client client, RestController restController) {
        super(settings, client);

        restController.registerHandler(RestRequest.Method.POST,"/_bulktag",this);
        restController.registerHandler(RestRequest.Method.PUT,"/_bulktag",this);
    }

    public void handleRequest(final RestRequest request, final RestChannel channel) {

        final Map<String,Object> pendingChanges=sourceAsMap(request.contentByteArray(),request.contentByteArrayOffset(),request.contentLength());

        // if pending changes is empty,just return
        if (pendingChanges.size() <= 0){
            XContentBuilder builder = null;
            try {
                builder = RestXContentBuilder.restContentBuilder(request);
                builder.startObject().field("reason","pending changes is empty").endObject();
                            channel.sendResponse(new XContentRestResponse(request,RestStatus.BAD_REQUEST, builder));
                return;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if(logger.isDebugEnabled()){
            logger.debug("pendingChanges({}):{}",pendingChanges.size(),pendingChanges.toString());
        }

        // create a scrollable cursor (matchAll for now)
        SearchResponse scrollResp = client.prepareSearch()
            .setSearchType(SearchType.SCAN)
            .setScroll(new TimeValue(60000))
            .setQuery(matchAllQuery())
            .setSize(10000).execute().actionGet();

        // send an immediate response so the client doesn't block   
        try {
            XContentBuilder builder = RestXContentBuilder.restContentBuilder(request);
            builder.startObject().field(Fields.OK, true);
            builder.field(Fields.STATUS, "started");
            builder.endObject();
            RestStatus status = OK;
            channel.sendResponse(new XContentRestResponse(request, status, builder));
        } catch (Exception e) {
            try{
                channel.sendResponse(new XContentThrowableRestResponse(request,e));
            } catch (IOException e1) {
                logger.error("Failed to send failure response",e1);
            }
            return;
        }

        logger.info("Bulk tag action started");

        // start scrolling over results
        while (true) {
            scrollResp = client.prepareSearchScroll(scrollResp.getScrollId())
                .setScroll(new TimeValue(600000))
                .execute()
                .actionGet();
            boolean hitsRead = false;
            BulkRequest bulkRequest = Requests.bulkRequest();

            // for each segment (100 x shard_count)
            for (SearchHit hit : scrollResp.getHits()) {
                hitsRead = true;
                final Map<String,Object> doc = hit.sourceAsMap();
                
                // update the existing document
                for (Iterator<String> iterator = pendingChanges.keySet().iterator(); iterator.hasNext(); ) {
                    String next =  iterator.next();
                    doc.put(next, pendingChanges.get(next));
                }

                // create an IndexRequest for this document
                IndexRequest indexRequest = new IndexRequest(hit.index(), hit.type(), hit.id());
                indexRequest.routing(request.param("routing"));
                indexRequest.parent(request.param("parent"));
                indexRequest.source(doc);
                indexRequest.timeout(request.paramAsTime("timeout", IndexRequest.DEFAULT_TIMEOUT));
                indexRequest.refresh(request.paramAsBoolean("refresh", indexRequest.refresh()));
                indexRequest.version(RestActions.parseVersion(request));
                indexRequest.versionType(VersionType.fromString(request.param("version_type"), indexRequest.versionType()));
                indexRequest.percolate(request.param("percolate", null));
                indexRequest.opType(IndexRequest.OpType.INDEX);
                
                String replicationType = request.param("replication");
                if (replicationType != null) {
                    indexRequest.replicationType(ReplicationType.fromString(replicationType));
                }

                String consistencyLevel = request.param("consistency");
                if (consistencyLevel != null) {
                    indexRequest.consistencyLevel(WriteConsistencyLevel.fromString(consistencyLevel));
                }
                // we just send a response, no need to fork
                indexRequest.listenerThreaded(true);
                // we don't spawn, then fork if local
                indexRequest.operationThreaded(true);

                // Build the bulk Request
                bulkRequest.add(indexRequest);
            }
            //Break condition: No hits are returned
            if (!hitsRead) {
                break;
            }
            
            // submit a bulk request 
            client.bulk(bulkRequest, new ActionListener<BulkResponse>() {
                public void onResponse(BulkResponse response) {
                    logger.info("Bulk request completed");
                    for (BulkItemResponse itemResponse : response) {
                        if (itemResponse.failed()) {
                            logger.error("Index request failed {index:{}, type:{}, id:{}, reason:{}}", 
                                itemResponse.index(),
                                itemResponse.type(),
                                itemResponse.id(),
                                itemResponse.failure().message());
                        }
                    }
                }
                
                public void onFailure(Throwable e) {
                    logger.error("Bulk request failed {reason:{}}", e); 
                }
            });
            logger.info("Submitting bulk request");
        }
        logger.info("Bulk tag action complete");
    }

    public static Map<String, Object> sourceAsMap(byte[] bytes, int offset, int length) {
          XContentParser parser = null;
          try {
              if (isCompressed(bytes, offset, length)) {
                  BytesStreamInput siBytes = new BytesStreamInput(bytes, offset, length);
                  LZFStreamInput siLzf = CachedStreamInput.cachedLzf(siBytes);
                  XContentType contentType = XContentFactory.xContentType(siLzf);
                  siLzf.resetToBufferStart();
                  parser = XContentFactory.xContent(contentType).createParser(siLzf);
                  return parser.map();
              } else {
                  parser = XContentFactory.xContent(bytes, offset, length).createParser(bytes, offset, length);
                  return parser.map();
              }
          } catch (Exception e) {
              throw new ElasticSearchParseException("Failed to parse source to map", e);
          } finally {
              if (parser != null) {
                  parser.close();
              }
          }
      }
   
    public static boolean isCompressed(final byte[] buffer, int offset, int length) {
        return length >= 2 && buffer[offset] == LZFChunk.BYTE_Z && buffer[offset + 1] == LZFChunk.BYTE_V;
    }

    static final class Fields {
        static final XContentBuilderString OK = new XContentBuilderString("ok");
        static final XContentBuilderString STATUS = new XContentBuilderString("status");
    }
}

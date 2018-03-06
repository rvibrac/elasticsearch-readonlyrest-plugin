package security;

import java.io.IOException;
import java.util.function.Function;

import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.IndexSearcher;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.logging.LoggerMessageFormat;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.engine.EngineException;
import org.elasticsearch.index.query.ParsedQuery;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.index.shard.IndexSearcherWrapper;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.ShardUtils;

import com.unboundid.util.args.ArgumentException;

import tech.beshu.ror.commons.Constants;

public class RoleIndexSearcherWrapper extends IndexSearcherWrapper {
    private final Logger logger;
    private final IndexSettings indexSettings;
    private final Function<ShardId, QueryShardContext> queryShardContextProvider;
    private final ThreadContext threadContext;
	private final Boolean enabled;
	
	public RoleIndexSearcherWrapper(IndexService indexService) throws Exception {
        if (indexService == null) {
            throw new ArgumentException("Please provide an indexService");
        }
        this.indexSettings = indexService.getIndexSettings();
		this.logger = Loggers.getLogger(this.getClass(), new String[0]);
        this.queryShardContextProvider = shardId -> indexService.newQueryShardContext(shardId.id(), null, null, null);
        this.threadContext = indexService.getThreadPool().getThreadContext();

		Settings configFileSettings = indexSettings.getSettings().getByPrefix("readonlyrest.");
		
		this.enabled = configFileSettings.getAsBoolean("enable", false);
	}

	@Override
	protected DirectoryReader wrap(DirectoryReader reader) {
		if (!this.enabled) {
			logger.warn("Document filtering not available. Return defaut reader");
			return reader;
		}

		UserTransient userTransient = UserTransient.Deserialize(threadContext.getHeader(Constants.USER_TRANSIENT));
		if (userTransient == null) {
			throw new IllegalStateException("Couldn't extract userTransient from threadContext.");
		}

        ShardId shardId = ShardUtils.extractShardId(reader);
		if (shardId == null) {
			throw new IllegalStateException(
					LoggerMessageFormat.format("Couldn't extract shardId from reader [{}]", new Object[] { reader }));
		}

        String indice = shardId.getIndexName();

		String filter = userTransient.getFilter();

		if (filter == null || filter.equals("")) {
			return reader;
        }
		
		try {
			BooleanQuery.Builder boolQuery = new BooleanQuery.Builder();
            boolQuery.setMinimumNumberShouldMatch(1);
            QueryShardContext queryShardContext = this.queryShardContextProvider.apply(shardId);
            XContentParser parser = JsonXContent.jsonXContent.createParser(queryShardContext.getXContentRegistry(), filter);
            QueryBuilder queryBuilder = queryShardContext.parseInnerQueryBuilder(parser);
            ParsedQuery parsedQuery = queryShardContext.toFilter(queryBuilder);
			boolQuery.add(parsedQuery.query(), BooleanClause.Occur.SHOULD);
            reader = DocumentFilterReader.wrap(reader, new ConstantScoreQuery(boolQuery.build()));
			return reader;
		} catch (IOException e) {
			this.logger.error("Unable to setup document security");
			throw ExceptionsHelper.convertToElastic((Exception) e);
		}
    }
    

	@Override
	protected IndexSearcher wrap(IndexSearcher indexSearcher) throws EngineException {
		return indexSearcher;
    }
}

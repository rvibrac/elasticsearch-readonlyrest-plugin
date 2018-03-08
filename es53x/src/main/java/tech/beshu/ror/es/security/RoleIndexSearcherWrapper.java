/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */

package tech.beshu.ror.es.security;

import static tech.beshu.ror.commons.Constants.SETTINGS_YAML_FILE;

import java.io.IOException;
import java.util.function.Function;

import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.IndexSearcher;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.common.logging.LoggerMessageFormat;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.env.Environment;
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
import tech.beshu.ror.commons.settings.BasicSettings;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.settings.SettingsUtils;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.es.ESContextImpl;

/*
 * @author Datasweet <contact@datasweet.fr>
 */
public class RoleIndexSearcherWrapper extends IndexSearcherWrapper {
    private final LoggerShim logger;
    private final IndexSettings indexSettings;
    private final Function<ShardId, QueryShardContext> queryShardContextProvider;
    private final ThreadContext threadContext;
	private final Boolean enabled;
	
	public RoleIndexSearcherWrapper(IndexService indexService) throws Exception {
        if (indexService == null) {
            throw new ArgumentException("Please provide an indexService");
        }
        this.indexSettings = indexService.getIndexSettings();
		Logger logger = Loggers.getLogger(this.getClass(), new String[0]);
        this.queryShardContextProvider = shardId -> indexService.newQueryShardContext(shardId.id(), null, null);
        this.threadContext = indexService.getThreadPool().getThreadContext();
        logger.info("Create new RoleIndexSearcher wrapper, [{}]", indexService.getIndexSettings().getIndex().getName());
		Settings configFileSettings = indexSettings.getSettings();
		Environment env = new Environment(configFileSettings);
		this.logger = ESContextImpl.mkLoggerShim(logger);
		BasicSettings baseSettings = BasicSettings.fromFile(this.logger, env.configFile().toAbsolutePath(), configFileSettings.getAsStructuredMap());
		this.enabled = baseSettings.isEnabled();
	}

	@Override
	protected DirectoryReader wrap(DirectoryReader reader) {
		if (!this.enabled) {
			logger.warn("Document filtering not available. Return defaut reader");
			return reader;
		}

		UserTransient userTransient = UserTransient.Deserialize(threadContext.getHeader(Constants.USER_TRANSIENT));
		if (userTransient == null) {
			logger.warn("Couldn't extract userTransient from threadContext.");
			return reader;
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
            XContentParser parser = XContentFactory.xContent(filter).createParser(queryShardContext.getXContentRegistry(), filter);
            QueryBuilder queryBuilder = queryShardContext.newParseContext(parser).parseInnerQueryBuilder().get();
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

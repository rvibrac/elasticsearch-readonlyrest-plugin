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

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.FilterDirectoryReader;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.BitSetIterator;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.FixedBitSet;
import org.elasticsearch.ExceptionsHelper;

// https://stackoverflow.com/questions/40949286/apply-lucene-query-on-bits
// https://github.com/apache/lucene-solr/blob/master/lucene/misc/src/java/org/apache/lucene/index/PKIndexSplitter.java#L127-L170

/*
 * @author Datasweet <contact@datasweet.fr>
 */
public final class DocumentFilterReader extends FilterLeafReader {

    private final Bits liveDocs;
    private final int numDocs;
    private final Boolean dlsEnabled;
    private final Boolean flsEnabled;
	private final HashSet<String> excludesSet;
	private final HashSet<String> includesSet;
	private String[] includes;
    private String[] excludes;
	private final FieldInfos flsFieldInfos;
    
    public static DocumentFilterDirectoryReader wrap(DirectoryReader in, Query filterQuery, Set<String> fields) throws IOException {
        return new DocumentFilterDirectoryReader(in, filterQuery, fields);
    }

    private DocumentFilterReader(LeafReader reader, Query query, Set<String> fields) throws IOException {
        super(reader);
        flsEnabled = fields != null && !fields.isEmpty();
        dlsEnabled = query != null;
        
        if (flsEnabled) {
        	final FieldInfos infos = reader.getFieldInfos();
        	this.includesSet = new HashSet<String>(fields.size());
            this.excludesSet = new HashSet<String>(fields.size());
            for (final String field : fields) {
            	final char firstChar = field.charAt(0);
            	if (firstChar == '~')
            		excludesSet.add(field.substring(1));
            	else
            		includesSet.add(field);
            }
            
            int i = 0;
            final FieldInfo[] fa = new FieldInfo[infos.size()];
            if (!excludesSet.isEmpty()) {
            	for (final FieldInfo info : infos) {
                    if (!excludesSet.contains(info.name)) {
                        fa[i++] = info;
                    }
                }
            } else {
                for (final String inc : includesSet) {
                	FieldInfo f;
                    if ((f = infos.fieldInfo(inc)) != null) {
                        fa[i++] = f;
                    }
                }
            }
            final FieldInfo[] tmp = new FieldInfo[i];
            System.arraycopy(fa, 0, tmp, 0, i);
            this.flsFieldInfos = new FieldInfos(tmp);
        } else {
        	this.includesSet = null;
        	this.excludesSet = null;
        	this.flsFieldInfos = null;
        }
        if (dlsEnabled) {
	        final IndexSearcher searcher = new IndexSearcher(this);
	        searcher.setQueryCache(null);
	        final boolean needsScores = false;
	        final Weight preserveWeight = searcher.createNormalizedWeight(query, needsScores);
	
	        final int maxDoc = this.in.maxDoc();
	        final FixedBitSet bits = new FixedBitSet(maxDoc);
	        final Scorer preverveScorer = preserveWeight.scorer(this.getContext());
	        if (preverveScorer != null) {
	            bits.or(preverveScorer.iterator());
	        }
	
	        if (in.hasDeletions()) {
	            final Bits oldLiveDocs = in.getLiveDocs();
	            assert oldLiveDocs != null;
	            final DocIdSetIterator it = new BitSetIterator(bits, 0L);
	            for (int i = it.nextDoc(); i != DocIdSetIterator.NO_MORE_DOCS; i = it.nextDoc()) {
	                if (!oldLiveDocs.get(i)) {
	                    bits.clear(i);
	                }
	            }
	        }
	
	        this.liveDocs = bits;
	        this.numDocs = bits.cardinality();
        } else {
        	this.liveDocs = null;
        	this.numDocs = -1;
        }
    }

    @Override
    public int numDocs() {
        return numDocs;
    }

    @Override
    public Bits getLiveDocs() {
        return liveDocs;
    }

    private static final class DocumentFilterDirectorySubReader extends FilterDirectoryReader.SubReaderWrapper {
        private final Query query;
        private final Set<String> fields;
        
        public DocumentFilterDirectorySubReader(Query filterQuery, Set<String> fields) {
            this.query = filterQuery;
            this.fields = fields;
        }

        @Override
        public LeafReader wrap(LeafReader reader) {
            try {
                return new DocumentFilterReader(reader, this.query, this.fields);
            } catch (Exception e) {
                throw ExceptionsHelper.convertToElastic(e);
            }
        }
    }

    public static final class DocumentFilterDirectoryReader extends FilterDirectoryReader {
        private final Query filterQuery;
        private final Set<String> fields;
        
        DocumentFilterDirectoryReader(DirectoryReader in, Query filterQuery, Set<String> fields) throws IOException {
            super(in, new DocumentFilterDirectorySubReader(filterQuery, fields));
            this.filterQuery = filterQuery;
            this.fields = fields;
        }

        @Override
        protected DirectoryReader doWrapDirectoryReader(DirectoryReader in) throws IOException {
            return new DocumentFilterDirectoryReader(in, this.filterQuery, this.fields);
        }

		@Override
		public CacheHelper getReaderCacheHelper() {
			return this.in.getReaderCacheHelper();
		}

    }

    private boolean isFls(final String name) {
        
        if(!flsEnabled) {
            return true;
        }
        
        return flsFieldInfos.fieldInfo(name) != null;
    }

    @Override
    public FieldInfos getFieldInfos() {    
        if(!flsEnabled) {
            return in.getFieldInfos();
        }
        
        return flsFieldInfos;
    }
    
	@Override
	public CacheHelper getCoreCacheHelper() {
		return this.in.getCoreCacheHelper();
	}

	@Override
	public CacheHelper getReaderCacheHelper() {
		return this.in.getReaderCacheHelper();
	}
}
/*
 * Copyright 2013-2017 Erudika. https://erudika.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.para.persistence;

import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.erudika.para.annotations.Locked;
import com.erudika.para.core.ParaObject;
import com.erudika.para.core.utils.ParaObjectUtils;
import static com.erudika.para.persistence.MongoDBUtils.getTable;
import com.erudika.para.utils.Config;
import com.erudika.para.utils.Pager;
import com.erudika.para.utils.Utils;
import com.mongodb.BasicDBObject;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import java.util.ArrayList;
import java.util.LinkedList;
import org.bson.conversions.Bson;

/**
 * MongoDB DAO implementation for Para.
 * @author Luca Venturella [lucaventurella@gmail.com]
 */
@Singleton
public class MongoDBDAO implements DAO {

	private static final Logger logger = LoggerFactory.getLogger(MongoDBDAO.class);
	private static final String _ID = "_id";
	private static final String _OBJECT_ID = "_ObjectId";

	/**
	 * Default constructor.
	 */
	public MongoDBDAO() { }

	/////////////////////////////////////////////
	//			CORE FUNCTIONS
	/////////////////////////////////////////////

	@Override
	public <P extends ParaObject> String create(String appid, P so) {
		if (so == null) {
			return null;
		}
		if (StringUtils.isBlank(so.getId())) {
			so.setId(MongoDBUtils.generateNewId());
			logger.debug("Generated id: " + so.getId());
		}
		if (so.getTimestamp() == null) {
			so.setTimestamp(Utils.timestamp());
		}
		so.setAppid(appid);
		createRow(so.getId(), appid, toRow(so, null, false, true));
		logger.debug("DAO.create() {}", so.getId());
		return so.getId();
	}

	@Override
	public <P extends ParaObject> P read(String appid, String key) {
		if (StringUtils.isBlank(key)) {
			return null;
		}
		P so = fromRow(readRow(key, appid));
		logger.debug("DAO.read() {} -> {}", key, so == null ? null : so.getType());
		return so != null ? so : null;
	}

	@Override
	public <P extends ParaObject> void update(String appid, P so) {
		if (so != null && so.getId() != null) {
			so.setUpdated(Utils.timestamp());
			updateRow(so.getId(), appid, toRow(so, Locked.class, true));
			logger.debug("DAO.update() {}", so.getId());
		}
	}

	@Override
	public <P extends ParaObject> void delete(String appid, P so) {
		if (so != null && so.getId() != null) {
			deleteRow(so.getId(), appid);
			logger.debug("DAO.delete() {}", so.getId());
		}
	}

	/////////////////////////////////////////////
	//				ROW FUNCTIONS
	/////////////////////////////////////////////

	private String createRow(String key, String appid, Document row) {
		if (StringUtils.isBlank(key) || StringUtils.isBlank(appid) || row == null || row.isEmpty()) {
			return null;
		}
		try {
			// if there isn't a document with the same id then create a new document
			// else replace the document with the same id with the new one
			getTable(appid).replaceOne(new Document(_ID, key), row, new UpdateOptions().upsert(true));
		} catch (Exception e) {
			logger.error(null, e);
		}
		return key;
	}

	//http://www.mkyong.com/mongodb/java-mongodb-update-document/
	private void updateRow(String key, String appid, Document row) {
		if (StringUtils.isBlank(key) || StringUtils.isBlank(appid) || row == null || row.isEmpty()) {
			return;
		}
		try {
			UpdateResult u = getTable(appid).updateOne(new Document(_ID, key), new Document("$set", row));
			logger.debug("key: " + key + " updated count: " + u.getModifiedCount());
		} catch (Exception e) {
			logger.error(null, e);
		}
	}

	private Document readRow(String key, String appid) {
		if (StringUtils.isBlank(key) || StringUtils.isBlank(appid)) {
			return null;
		}
		Document row = null;
		try {
			row = getTable(appid).find(new Document(_ID, key)).first();
			logger.debug("id: " + key + " row null: " + (row == null));
		} catch (Exception e) {
			logger.error(null, e);
		}
		return (row == null || row.isEmpty()) ? null : row;
	}

	private void deleteRow(String key, String appid) {
		if (StringUtils.isBlank(key) || StringUtils.isBlank(appid)) {
			return;
		}
		try {
			DeleteResult d = getTable(appid).deleteOne(new Document(_ID, key));
			logger.debug("key: " + key + " deleted count: " + d.getDeletedCount());
		} catch (Exception e) {
			logger.error(null, e);
		}
	}

	/////////////////////////////////////////////
	//				READ ALL FUNCTIONS
	/////////////////////////////////////////////

	@Override
	public <P extends ParaObject> void createAll(String appid, List<P> objects) {
		if (objects == null || objects.isEmpty() || StringUtils.isBlank(appid)) {
			return;
		}
		List<Document> documents = new ArrayList<Document>();
		for (ParaObject so : objects) {
			if (so != null) {
				if (StringUtils.isBlank(so.getId())) {
					so.setId(MongoDBUtils.generateNewId());
					logger.debug("Generated id: " + so.getId());
				}
				if (so.getTimestamp() == null) {
					so.setTimestamp(Utils.timestamp());
				}
				so.setAppid(appid);
				documents.add(toRow(so, null, false, true));
			}
		}
		if (!documents.isEmpty()) {
			getTable(appid).insertMany(documents);
		}
		logger.debug("DAO.createAll() {}", (objects == null) ? 0 : objects.size());
	}

	@Override
	public <P extends ParaObject> Map<String, P> readAll(String appid, List<String> keys, boolean getAllColumns) {
		if (keys == null || keys.isEmpty() || StringUtils.isBlank(appid)) {
			return new LinkedHashMap<String, P>();
		}
		Map<String, P> results = new LinkedHashMap<String, P>(keys.size(), 0.75f, true);
		BasicDBObject inQuery = new BasicDBObject();
		inQuery.put(_ID, new BasicDBObject("$in", keys));

		MongoCursor<Document> cursor = getTable(appid).find(inQuery).iterator();
		while (cursor.hasNext()) {
			Document d = cursor.next();
			P obj = fromRow(d);
			results.put(d.getString(_ID), obj);
		}

		logger.debug("DAO.readAll() {}", results.size());
		return results;
	}

	@Override
	public <P extends ParaObject> List<P> readPage(String appid, Pager pager) {
		LinkedList<P> results = new LinkedList<P>();
		if (StringUtils.isBlank(appid)) {
			return results;
		}
		if (pager == null) {
			pager = new Pager();
		}
		try {
			String lastKey = pager.getLastKey();
			MongoCursor<Document> cursor;
			Bson filter = Filters.gt(_OBJECT_ID, lastKey);
			if (lastKey == null) {
				cursor = getTable(appid).find().batchSize(pager.getLimit()).limit(pager.getLimit()).iterator();
			} else {
				cursor = getTable(appid).find(filter).batchSize(pager.getLimit()).limit(pager.getLimit()).iterator();
			}
			while (cursor.hasNext()) {
				Map<String, Object> row = documentToMap(cursor.next());
				P obj = fromRow(row);
				if (obj != null) {
					results.add(obj);
					pager.setLastKey((String) row.get(_OBJECT_ID));
				}
			}
			if (!results.isEmpty()) {
				pager.setCount(pager.getCount() + results.size());
			}
		} catch (Exception e) {
			logger.error(null, e);
		}
		logger.debug("readPage() page: {}, results:", pager.getPage(), results.size());
		return results;
	}

	@Override
	public <P extends ParaObject> void updateAll(String appid, List<P> objects) {
		if (StringUtils.isBlank(appid) || objects == null) {
			return;
		}
		try {
			ArrayList<WriteModel<Document>> updates = new ArrayList<WriteModel<Document>>();
			List<String> ids = new ArrayList<String>(objects.size());
			for (P object : objects) {
				if (object != null) {
					object.setUpdated(Utils.timestamp());
					Document id = new Document(_ID, object.getId());
					Document data = new Document("$set", toRow(object, Locked.class, true));
					UpdateOneModel<Document> um = new UpdateOneModel<Document>(id, data);
					updates.add(um);
					ids.add(object.getId());
				}
			}
			BulkWriteResult res = getTable(appid).bulkWrite(updates, new BulkWriteOptions().ordered(true));
			logger.debug("Updated: " + res.getModifiedCount() + ", keys: " + ids);
		} catch (Exception e) {
			logger.error(null, e);
		}
		logger.debug("DAO.updateAll() {}", (objects == null) ? 0 : objects.size());
	}

	@Override
	public <P extends ParaObject> void deleteAll(String appid, List<P> objects) {
		if (objects == null || objects.isEmpty() || StringUtils.isBlank(appid)) {
			return;
		}
		BasicDBObject query = new BasicDBObject();
		List<String> list = new ArrayList<String>();
		for (ParaObject object : objects) {
			list.add(object.getId());
		}
		query.put(_ID, new BasicDBObject("$in", list));
		getTable(appid).deleteMany(query);
		logger.debug("DAO.deleteAll() {}", objects.size());
	}

	/////////////////////////////////////////////
	//				MISC FUNCTIONS
	/////////////////////////////////////////////

	private <P extends ParaObject> Document toRow(P so, Class<? extends Annotation> filter, boolean setNullFields) {
		return toRow(so, filter, setNullFields, false);
	}

	private <P extends ParaObject> Document toRow(P so, Class<? extends Annotation> filter,
			boolean setNullFields, boolean setMongoId) {
		Document row = new Document();
		if (so == null) {
			return row;
		}
		// field values will be stored as they are - object structure and types will be preserved
		for (Entry<String, Object> entry : ParaObjectUtils.getAnnotatedFields(so, filter, false).entrySet()) {
			Object value = entry.getValue();
			if ((value != null && !StringUtils.isBlank(value.toString())) || setNullFields) {
				// "id" in ParaObject is translated to "_ID" mongodb
				if (entry.getKey().equals(Config._ID)) {
					row.put(_ID, value.toString());
				} else {
					row.put(entry.getKey(), value);
				}
				if (setMongoId) {
					// we add the native MongoDB id which will later be used for pagination and sorting
					row.put(_OBJECT_ID, MongoDBUtils.generateNewId());
				}
			}
		}
		return row;
	}

	private <P extends ParaObject> P fromRow(Document row) {
		return fromRow(documentToMap(row));
	}

	private <P extends ParaObject> P fromRow(Map<String, Object> row) {
		return ParaObjectUtils.setAnnotatedFields(row);
	}

	private Map<String, Object> documentToMap(Document row) {
		if (row == null || row.isEmpty()) {
			logger.debug("row is null or empty");
			return null;
		}
		Map<String, Object> props = new HashMap<String, Object>();
		for (Entry<String, Object> col : row.entrySet()) {
			// "_ID" mongodb is translated to "id" in ParaObject
			if (col.getKey().equals(_ID)) {
				props.put(Config._ID, col.getValue());
			} else {
				props.put(col.getKey(), col.getValue());
			}
		}
		return props;
	}

	//////////////////////////////////////////////////////

	@Override
	public <P extends ParaObject> String create(P so) {
		return create(Config.getRootAppIdentifier(), so);
	}

	@Override
	public <P extends ParaObject> P read(String key) {
		return read(Config.getRootAppIdentifier(), key);
	}

	@Override
	public <P extends ParaObject> void update(P so) {
		update(Config.getRootAppIdentifier(), so);
	}

	@Override
	public <P extends ParaObject> void delete(P so) {
		delete(Config.getRootAppIdentifier(), so);
	}

	@Override
	public <P extends ParaObject> void createAll(List<P> objects) {
		createAll(Config.getRootAppIdentifier(), objects);
	}

	@Override
	public <P extends ParaObject> Map<String, P> readAll(List<String> keys, boolean getAllColumns) {
		return readAll(Config.getRootAppIdentifier(), keys, getAllColumns);
	}

	@Override
	public <P extends ParaObject> List<P> readPage(Pager pager) {
		return readPage(Config.getRootAppIdentifier(), pager);
	}

	@Override
	public <P extends ParaObject> void updateAll(List<P> objects) {
		updateAll(Config.getRootAppIdentifier(), objects);
	}

	@Override
	public <P extends ParaObject> void deleteAll(List<P> objects) {
		deleteAll(Config.getRootAppIdentifier(), objects);
	}

}

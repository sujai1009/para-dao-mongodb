/*
 * Copyright 2013-2019 Erudika. https://erudika.com
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

import com.erudika.para.DestroyListener;
import com.erudika.para.Para;
import com.erudika.para.core.App;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.erudika.para.utils.Config;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoClientURI;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoIterable;

import javax.inject.Singleton;

/**
 * MongoDB DAO utilities for Para.
 * @author Luca Venturella [lucaventurella@gmail.com]
 */
@Singleton
public final class MongoDBUtils {

	private static final Logger logger = LoggerFactory.getLogger(MongoDBUtils.class);
	private static MongoClient mongodbClient;
	private static MongoDatabase mongodb;
	private static final String DBURI = Config.getConfigParam("mongodb.uri", "");
	private static final String DBHOST = Config.getConfigParam("mongodb.host", "localhost");
	private static final int DBPORT = Config.getConfigInt("mongodb.port", 27017);
	private static final boolean SSL = Config.getConfigBoolean("mongodb.ssl_enabled", false);
	private static final boolean SSL_ALLOW_ALL = Config.getConfigBoolean("mongodb.ssl_allow_all", false);
	private static final String DBNAME = Config.getConfigParam("mongodb.database", Config.getRootAppIdentifier());
	private static final String DBUSER = Config.getConfigParam("mongodb.user", "");
	private static final String DBPASS = Config.getConfigParam("mongodb.password", "");

	private MongoDBUtils() { }

	/**
	 * Returns a client instance for MongoDB.
	 * @return a client that talks to MongoDB
	 */
	public static MongoDatabase getClient() {
		if (mongodb != null) {
			return mongodb;
		}

		MongoClientOptions options = MongoClientOptions.builder().
				sslEnabled(SSL).sslInvalidHostNameAllowed(SSL_ALLOW_ALL).build();

		if (!StringUtils.isBlank(DBURI)) {
			logger.info("MongoDB uri: " + DBURI.replaceAll("mongodb://.*@", "mongodb://<user:password>@") + ", database: " + DBNAME);
			MongoClientURI uri = new MongoClientURI(DBURI, new MongoClientOptions.Builder(options));
			mongodbClient = new MongoClient(uri);
		} else {
			logger.info("MongoDB host: " + DBHOST + ":" + DBPORT + ", database: " + DBNAME);
			ServerAddress s = new ServerAddress(DBHOST, DBPORT);

			if (!StringUtils.isBlank(DBUSER) && !StringUtils.isBlank(DBPASS)) {
				MongoCredential credential = MongoCredential.createCredential(DBUSER, DBNAME, DBPASS.toCharArray());
				mongodbClient = new MongoClient(s, credential, options);
			} else {
				mongodbClient = new MongoClient(s, options);
			}
		}

		mongodb = mongodbClient.getDatabase(DBNAME);

		if (!existsTable(Config.getRootAppIdentifier())) {
			createTable(Config.getRootAppIdentifier());
		}

		Para.addDestroyListener(new DestroyListener() {
			public void onDestroy() {
				shutdownClient();
			}
		});

		return mongodb;
	}

	/**
	 * Stops the client and releases resources.
	 * You can tell Para to call this on shutdown using {@code Para.addDestroyListener()}
	 */
	public static void shutdownClient() {
		if (mongodbClient != null) {
			mongodbClient.close();
			mongodbClient = null;
		}
	}

	/**
	 * Checks if the main table exists in the database.
	 * @param appid name of the {@link com.erudika.para.core.App}
	 * @return true if the table exists
	 */
	public static boolean existsTable(String appid) {
		if (StringUtils.isBlank(appid)) {
			return false;
		}
		try {
			appid = getTableNameForAppid(appid);
			MongoIterable<String> collectionNames = getClient().listCollectionNames();
			for (final String name : collectionNames) {
				if (name.equalsIgnoreCase(appid)) {
					return true;
				}
			}
			return false;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Creates a table in MongoDB.
	 * @param appid name of the {@link com.erudika.para.core.App}
	 * @return true if created
	 */
	public static boolean createTable(String appid) {
		if (StringUtils.isBlank(appid) || StringUtils.containsWhitespace(appid) || existsTable(appid)) {
			return false;
		}
		try {
			String table = getTableNameForAppid(appid);
			getClient().createCollection(table);
			// *** Don't need to create a secondary index here until when will be developed a full "Search" implementation for MongoDB ***
			// create a default seconday index for parentid field as string
			// getClient().getCollection(appid).createIndex(Indexes.text(Config._PARENTID));
			logger.info("Created MongoDB table '{}'.", table);
		} catch (Exception e) {
			logger.error(null, e);
			return false;
		}
		return true;
	}

	/**
	 * Deletes the main table from MongoDB.
	 * @param appid name of the {@link com.erudika.para.core.App}
	 * @return true if deleted
	 */
	public static boolean deleteTable(String appid) {
		if (StringUtils.isBlank(appid) || !existsTable(appid)) {
			return false;
		}
		try {
			MongoCollection<Document> collection = getTable(appid);
			if (collection != null) {
				collection.drop();
			}
			logger.info("Deleted MongoDB table '{}'.", getTableNameForAppid(appid));
		} catch (Exception e) {
			logger.error(null, e);
			return false;
		}
		return false;
	}

	/**
	 * Gives count information about a MongoDB table.
	 * @param appid name of the collection
	 * @return a long
	 */
	public static long getTableCount(final String appid) {
		if (StringUtils.isBlank(appid)) {
			return -1;
		}
		try {
			MongoCollection<Document> collection = getTable(appid);
			return (collection == null) ? 0 : collection.countDocuments();
		} catch (Exception e) {
			logger.error(null, e);
		}
		return -1;
	}

	/**
	 * Get the mongodb table requested.
	 * @param appid name of the collection
	 * @return a Mongo collection
	 */
	public static MongoCollection<Document> getTable(String appid) {
		try {
			return getClient().getCollection(getTableNameForAppid(appid));
		} catch (Exception e) {
			logger.error(null, e);
		}
		return null;
	}

	/**
	 * Lists all table names for this account.
	 * @return a list of MongoDB tables
	 */
	public static MongoIterable<String> listAllTables() {
		MongoIterable<String> collectionNames = getClient().listCollectionNames();
		return collectionNames;
	}

	/**
	 * Returns the table name for a given app id. Table names are usually in the form 'prefix-appid'.
	 * @param appIdentifier app id
	 * @return the table name
	 */
	public static String getTableNameForAppid(String appIdentifier) {
		if (StringUtils.isBlank(appIdentifier)) {
			return null;
		} else {
			return (App.isRoot(appIdentifier) || appIdentifier.startsWith(Config.PARA.concat("-"))) ?
					appIdentifier : Config.PARA + "-" + appIdentifier;
		}
	}

	/**
	 * Create a new unique objectid for MongoDB.
	 * @return the objectid as string
	 */
	public static String generateNewId() {
		return new ObjectId().toHexString();
	}
}

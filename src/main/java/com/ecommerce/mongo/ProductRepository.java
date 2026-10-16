package com.ecommerce.mongo;

import com.mongodb.DuplicateKeyException;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Product catalog — MongoDB ONLY. Reads/writes never touch PostgreSQL/ES. */
@Repository
public class ProductRepository {

    public static final String COLLECTION = "products";

    private final MongoTemplate mongo;

    public ProductRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public void ensureIndexes() {
        try {
            mongo.indexOps(COLLECTION).ensureIndex(new Index().on("sku", Sort.Direction.ASC).unique());
            mongo.indexOps(COLLECTION).ensureIndex(new Index().on("category", Sort.Direction.ASC));
            mongo.indexOps(COLLECTION).ensureIndex(new Index().on("active", Sort.Direction.ASC));
        } catch (Exception ignored) {
            // best effort; indexes may already exist
        }
    }

    public static Map<String, Object> serialize(Document doc) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(doc);
        Object id = out.remove("_id");
        out.put("id", id == null ? null : id.toString());
        return out;
    }

    public List<Map<String, Object>> list(String category, String q, Boolean active) {
        Query query = new Query();
        if (active != null) query.addCriteria(Criteria.where("active").is(active));
        if (category != null && !category.isBlank()) query.addCriteria(Criteria.where("category").is(category));
        if (q != null && !q.isBlank()) {
            Pattern rx = Pattern.compile(Pattern.quote(q), Pattern.CASE_INSENSITIVE);
            query.addCriteria(new Criteria().orOperator(
                    Criteria.where("title").regex(rx),
                    Criteria.where("description").regex(rx),
                    Criteria.where("tags").regex(rx)));
        }
        query.with(Sort.by(Sort.Direction.ASC, "title"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Document d : mongo.find(query, Document.class, COLLECTION)) {
            out.add(serialize(d));
        }
        return out;
    }

    public Map<String, Object> findById(String productId) {
        ObjectId oid;
        try {
            oid = new ObjectId(productId);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Document d = mongo.findById(oid, Document.class, COLLECTION);
        return d == null ? null : serialize(d);
    }

    public Map<String, Object> findBySku(String sku) {
        Document d = mongo.findOne(Query.query(Criteria.where("sku").is(sku)), Document.class, COLLECTION);
        return d == null ? null : serialize(d);
    }

    public Map<String, Object> create(Map<String, Object> payload) {
        Document doc = new Document(payload);
        doc.put("updated_at", Instant.now());
        try {
            mongo.insert(doc, COLLECTION);
        } catch (Exception e) {
            // Mongo DuplicateKeyException or Spring's translated DataIntegrityViolationException
            String msg = String.valueOf(e.getMessage());
            if (e instanceof DuplicateKeyException
                    || e instanceof org.springframework.dao.DuplicateKeyException
                    || e instanceof org.springframework.dao.DataIntegrityViolationException
                    || msg.contains("duplicate key") || msg.contains("E11000")) {
                throw new DuplicateSkuException("duplicate sku: " + payload.get("sku"), e);
            }
            throw e;
        }
        return serialize(doc);
    }

    /** Thrown when a product SKU already exists. Not an IllegalArgumentException
     *  to avoid Spring's exception translation wrapping it. */
    public static class DuplicateSkuException extends RuntimeException {
        public DuplicateSkuException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public Map<String, Object> update(String productId, Map<String, Object> patch) {
        ObjectId oid;
        try {
            oid = new ObjectId(productId);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Update update = new Update();
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            update.set(e.getKey(), e.getValue());
        }
        update.set("updated_at", Instant.now());
        com.mongodb.client.result.UpdateResult r;
        try {
            r = mongo.updateFirst(Query.query(Criteria.where("_id").is(oid)), update, COLLECTION);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            if (e instanceof DuplicateKeyException
                    || e instanceof org.springframework.dao.DuplicateKeyException
                    || msg.contains("duplicate key") || msg.contains("E11000")) {
                throw new DuplicateSkuException("duplicate sku: " + patch.get("sku"), e);
            }
            throw e;
        }
        if (r.getMatchedCount() == 0) return null;
        return findById(productId);
    }

    public long countActive(boolean active) {
        return mongo.count(Query.query(Criteria.where("active").is(active)), COLLECTION);
    }

    public void deleteAll() {
        mongo.dropCollection(COLLECTION);
    }
}

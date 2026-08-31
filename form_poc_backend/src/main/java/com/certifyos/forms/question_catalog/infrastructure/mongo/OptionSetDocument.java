package com.certifyos.forms.question_catalog.infrastructure.mongo;

import com.certifyos.forms.question_catalog.domain.OptionSet;
import io.quarkus.mongodb.panache.common.MongoEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.codecs.pojo.annotations.BsonId;

/** Persistence shape for {@link OptionSet}. */
@MongoEntity(collection = "option_sets")
public class OptionSetDocument {

    @BsonId
    public String id;

    public String tenantId;
    public String key;
    public String name;
    public boolean active = true;
    public List<OptionDoc> options = new ArrayList<>();

    public static class OptionDoc {
        public String value;
        public String label;

        /** Filtering axes. A new filtering rule is a new tag here, never a code change. */
        public Map<String, List<String>> tags = new LinkedHashMap<>();
    }

    public static OptionSetDocument from(OptionSet set) {
        OptionSetDocument doc = new OptionSetDocument();
        doc.id = set.id();
        doc.tenantId = set.tenantId();
        doc.key = set.key();
        doc.name = set.name();
        doc.active = set.active();
        for (OptionSet.Option option : set.options()) {
            OptionDoc o = new OptionDoc();
            o.value = option.value();
            o.label = option.label();
            o.tags = new LinkedHashMap<>(option.tags());
            doc.options.add(o);
        }
        return doc;
    }

    public OptionSet toDomain() {
        List<OptionSet.Option> domainOptions = new ArrayList<>();
        options.forEach(o -> domainOptions.add(new OptionSet.Option(o.value, o.label, o.tags)));
        return new OptionSet(id, tenantId, key, name, domainOptions, active);
    }
}

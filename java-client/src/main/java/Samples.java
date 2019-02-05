import com.couchbase.client.core.error.CASMismatchException;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.json.JsonArray;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.kv.GetResult;
import com.couchbase.client.java.kv.MutateInOps;
import com.couchbase.client.java.kv.MutateInResult;
import com.couchbase.client.java.kv.MutationResult;
import com.couchbase.client.java.kv.PersistTo;
import com.couchbase.client.java.kv.ReplicateTo;

import java.util.Optional;

import static com.couchbase.client.java.kv.GetOptions.getOptions;
import static com.couchbase.client.java.kv.RemoveOptions.removeOptions;
import static com.couchbase.client.java.kv.ReplaceOptions.replaceOptions;

public class Samples {

  public static void main(String... args) {

    Cluster cluster = Cluster.connect(
      "127.0.0.1", "Administrator", "password"
    );

    Bucket bucket = cluster.bucket("travel-sample");

    Collection collection = bucket.defaultCollection();

    Optional<GetResult> airport = collection.get("airport_1291", getOptions()
      .project("airportname", "city")
      .withExpiration(true)
    );

    System.out.println(airport);

  }

  static void scenarioA(final Collection collection) {
    Optional<GetResult> document = collection.get("id");

    if (document.isPresent()) {
      JsonObject content = document.get().contentAsObject();
      content.put("modified", true);
      MutationResult result = collection.replace("id", content);
    }
  }

  static void scenarioB(final Collection collection) {
    Optional<GetResult> document = collection.get("id", getOptions().project("users"));

    if (document.isPresent()) {
      JsonArray content = document.get().contentAsArray();
      content.add(true);
      MutateInResult result = collection.mutateIn("id", MutateInOps.mutateInOps().replace("users", content));
    }
  }

  static void scenarioC(final Collection collection) {
    MutationResult result = collection.remove("id", removeOptions().withDurability(PersistTo.ONE, ReplicateTo.NONE));
  }

  static void scenarioD(final Collection collection) {
    do {
      GetResult read = collection.get("id").get();
      JsonObject content = read.contentAsObject();
      content.put("modified", true);
      try {
        MutationResult result = collection.replace("id", content, replaceOptions().cas(read.cas()));
      } catch (CASMismatchException ex) {
        continue;
      }
    } while(false);
  }

  static void scenarioE(final Collection collection) {
    Optional<GetResult> document = collection.get("id");

    if (document.isPresent()) {
      Entity content = document.get().contentAs(Entity.class);
      content.modified = true;
      MutationResult result = collection.replace("id", content);
    }
  }

  static void scenarioF(final Collection collection) {
    Optional<GetResult> document = collection.get("id");

    if (document.isPresent()) {
      Entity content = document.get().contentAs(Entity.class);
      content.modified = true;
      MutationResult result = collection.replace("id", content);
    }
  }

  class Entity {
    boolean modified;
  }

}

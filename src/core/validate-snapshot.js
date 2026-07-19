import Ajv from "ajv";
import addFormats from "ajv-formats";
import schema from "../../contract/snapshot-v1.schema.json" with { type: "json" };

const ajv = new Ajv({ allErrors: true, strict: true });
addFormats(ajv);
const validate = ajv.compile(schema);

export function validatePublicSnapshot(snapshot) {
  return validate(snapshot);
}

export function assertPublicSnapshot(snapshot) {
  if (!validatePublicSnapshot(snapshot)) {
    throw new Error("snapshot does not conform to the public v1 contract");
  }
  return snapshot;
}

# ime/ampk — bundled Amharic model

`AmharicLanguageProvider` loads its prediction model from this directory:

- `ngram.json` — trigram language model (next-word)
- `dictionary.json` — frequency-ranked completion dictionary (current-word)
- `manifest.json` — provenance + build stats

**These files are generated and git-ignored** (the model is a few MB — too large to push
reliably over some connections, and it is a build artifact, not source). Generate them from the
[am-predictive-kb](https://github.com/aadmassu1/am-predictive-kb) project:

```
py -3.12 scripts/build_amharic_model.py
```

That downloads the Amharic Wikipedia snapshot (CC BY-SA), builds a pruned on-device bundle, and
copies the three files here. Without them the keyboard still builds and runs — it just returns no
suggestions until the model is present.

Model provenance / licensing: see `pipeline/license_ledger.csv` in am-predictive-kb.

using System;
using System.Collections.Generic;
using System.IO;

namespace AIGames.EditorTools
{
    /// <summary>
    /// Resolves Android signing material from environment variables (preferred,
    /// for CI) and/or keystore/keystore.properties (local dev). Never hard-codes
    /// secrets. Environment variables take precedence over the properties file.
    /// </summary>
    public static class KeystoreConfig
    {
        public struct Signing
        {
            public string StoreFile;       // absolute path
            public string StorePassword;
            public string KeyAlias;
            public string KeyPassword;

            public bool IsComplete =>
                !string.IsNullOrEmpty(StoreFile) && File.Exists(StoreFile) &&
                !string.IsNullOrEmpty(StorePassword) &&
                !string.IsNullOrEmpty(KeyAlias) &&
                !string.IsNullOrEmpty(KeyPassword);
        }

        public static Signing Resolve(string projectRoot)
        {
            var s = new Signing
            {
                StoreFile     = Environment.GetEnvironmentVariable("AIGAMES_KEYSTORE_PATH"),
                StorePassword = Environment.GetEnvironmentVariable("AIGAMES_KEYSTORE_PASSWORD"),
                KeyAlias      = Environment.GetEnvironmentVariable("AIGAMES_KEY_ALIAS"),
                KeyPassword   = Environment.GetEnvironmentVariable("AIGAMES_KEY_PASSWORD"),
            };

            string propsPath = Path.Combine(projectRoot, "keystore", "keystore.properties");
            if (File.Exists(propsPath))
            {
                Dictionary<string, string> p = Parse(propsPath);
                if (string.IsNullOrEmpty(s.StoreFile) && p.TryGetValue("storeFile", out var sf))
                    s.StoreFile = sf;
                if (string.IsNullOrEmpty(s.StorePassword) && p.TryGetValue("storePassword", out var sp))
                    s.StorePassword = sp;
                if (string.IsNullOrEmpty(s.KeyAlias) && p.TryGetValue("keyAlias", out var ka))
                    s.KeyAlias = ka;
                if (string.IsNullOrEmpty(s.KeyPassword) && p.TryGetValue("keyPassword", out var kp))
                    s.KeyPassword = kp;
            }

            // Normalize a relative storeFile against the project root.
            if (!string.IsNullOrEmpty(s.StoreFile) && !Path.IsPathRooted(s.StoreFile))
                s.StoreFile = Path.GetFullPath(Path.Combine(projectRoot, s.StoreFile));

            return s;
        }

        private static Dictionary<string, string> Parse(string path)
        {
            var dict = new Dictionary<string, string>();
            foreach (string raw in File.ReadAllLines(path))
            {
                string line = raw.Trim();
                if (line.Length == 0 || line.StartsWith("#")) continue;
                int idx = line.IndexOf('=');
                if (idx <= 0) continue;
                dict[line.Substring(0, idx).Trim()] = line.Substring(idx + 1).Trim();
            }
            return dict;
        }
    }
}

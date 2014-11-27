(ns mvxcvi.crypto.pgp.data
  "Data encryption and decryption functions.

  This namespace makes use of the concept of _encryptors_ and _decryptors_.
  These are values used to encipher and decipher data, respectively. A
  collection of encryptors may be provided to the encryption functions, and
  the any corresponding decryptor will be able to read the resulting message.

  An encryptor may be a passphrase string or a public-key object. A decryptor
  may be a passphrase string, a private-key object, or a function that accepts
  a key id and returns the corresponding private-key."
  (:require
    [byte-streams :as bytes]
    [clojure.java.io :as io]
    (mvxcvi.crypto.pgp
      [tags :as tags]
      [util :refer [key-id public-key private-key arg-coll arg-map]]))
  (:import
    (java.io
      ByteArrayOutputStream
      FilterOutputStream
      InputStream
      OutputStream)
    java.nio.ByteBuffer
    java.security.SecureRandom
    java.util.Date
    (org.bouncycastle.bcpg
      ArmoredOutputStream)
    (org.bouncycastle.openpgp
      PGPPBEEncryptedData
      PGPCompressedData
      PGPCompressedDataGenerator
      PGPEncryptedDataGenerator
      PGPEncryptedDataList
      PGPLiteralData
      PGPLiteralDataGenerator
      PGPObjectFactory
      PGPPublicKeyEncryptedData
      PGPUtil)
    (org.bouncycastle.openpgp.operator.bc
      BcPGPDataEncryptorBuilder
      BcPBEDataDecryptorFactory
      BcPBEKeyEncryptionMethodGenerator
      BcPGPDigestCalculatorProvider
      BcPublicKeyDataDecryptorFactory
      BcPublicKeyKeyEncryptionMethodGenerator)))


; TODO: idea - have read-message return a map with info about the message
; instead of direct byte content. For example - algorithm compressed with, ids
; of keys encrypted for, cipher encrypted with, filename, mtime, etc.


;; ## PGP Data Encoding

(defprotocol DataPacket
  "Protocol for decryptable/unpackable data objects."

  (unpack-data
    [data opts]
    "Recursively unpacks a data packet and returns a nested sequence byte arrays
    containing the content. The decryptor is used to access encrypted packets.
    Throws an exception if encrypted data cannot be read."))


(defn- read-pgp-objects
  "Decodes a sequence of PGP objects from an input stream, unpacking each
  object's data."
  [opts ^InputStream input]
  (let [factory (PGPObjectFactory. input)]
    (->>
      (repeatedly #(.nextObject factory))
      (take-while some?)
      (map #(unpack-data % opts)))))


(defn armored-data-stream
  "Wraps an `OutputStream` with an armored data stream. Packets written to this
  stream will be output in ASCII encoded Base64."
  ^OutputStream
  [^OutputStream output]
  (ArmoredOutputStream. output))



;; ## Literal Data Packets

(defn literal-data-stream
  "Wraps an `OutputStream` with a literal data generator, returning another
  stream. Typically, the wrapped stream is a compressed data stream or
  encrypted data stream.

  Data written to the returned stream will write a literal data packet to the
  wrapped output stream. If the data is longer than the buffer size, the packet
  is written in chunks in a streaming fashion.

  Options may be provided to customize the packet:

  - `:buffer-size` maximum number of bytes per chunk
  - `:data-type` PGP document type, binary by default
  - `:filename` string giving the 'filename' of the data
  - `:mtime` modification time of the packet contents, defaults to the current time"
  ^OutputStream
  [^OutputStream output & opts]
  (let [{:keys [buffer-size data-type filename ^Date mtime]
         :or {buffer-size 4096
              data-type   PGPLiteralData/BINARY
              filename    PGPLiteralData/CONSOLE
              mtime       PGPLiteralData/NOW}}
        (arg-map opts)]
    (.open (PGPLiteralDataGenerator.)
           output
           (char data-type)
           (str filename)
           mtime
           (byte-array buffer-size))))


;; Read the literal data bytes from the packet.
(extend-protocol DataPacket
  PGPLiteralData

  (unpack-data
    [data opts]
    (bytes/to-byte-array (.getInputStream data))))



;; ## Compressed Data Packets

(defn compressed-data-stream
  "Wraps an `OutputStream` with a compressed data generator, returning another
  stream. Typically, literal data packets will be written to this stream, which
  are compressed and written to an underlying encryption stream."
  ^OutputStream
  [^OutputStream output algorithm]
  (.open (PGPCompressedDataGenerator.
           (tags/compression-algorithm algorithm))
         output))


;; Decompress the data contained in the packet.
(extend-protocol DataPacket
  PGPCompressedData

  (unpack-data
    [data opts]
    (->>
      (.getDataStream data)
      (read-pgp-objects opts)
      doall)))



;; ## Encrypted Data Packets

(defn- add-encryption-method!
  "Adds an encryption method to an encrypted data generator. Returns the updated
  generator."
  ^PGPEncryptedDataGenerator
  [^PGPEncryptedDataGenerator generator encryptor]
  (cond
    (string? encryptor)
    (.addMethod generator
      (BcPBEKeyEncryptionMethodGenerator.
        (.toCharArray ^String encryptor)))

    (public-key encryptor)
    (.addMethod generator
      (BcPublicKeyKeyEncryptionMethodGenerator.
        (public-key encryptor)))

    :else
    (throw (IllegalArgumentException.
             (str "Don't know how to encrypt data with " (pr-str encryptor)))))
  generator)


(defn encrypted-data-stream
  "Wraps an `OutputStream` with an encrypted data generator, returning another
  stream. The data written to the stream will be encrypted with a symmetric
  session key, which is then encrypted for each of the given public keys.

  Typically, the data written to this will consist of compressed data packets.
  If the data is longer than the buffer size, the packet is written in chunks
  in a streaming fashion.

  Options may be provided to customize the packet:

  - `:buffer-size`     maximum number of bytes per chunk
  - `:integrity-check` whether to include a Modification Detection Code packet
  - `:random`          custom random number generator"
  ^OutputStream
  [^OutputStream output cipher encryptors & opts]
  (let [encryptors (arg-coll encryptors)
        {:keys [buffer-size integrity-check random]
         :or {buffer-size 4096
              integrity-check true}}
        (arg-map opts)]
    (when (empty? (remove nil? encryptors))
      (throw (IllegalArgumentException.
               "Cannot encrypt data stream without encryptors.")))
    (when (< 1 (count (filter string? encryptors)))
      (throw (IllegalArgumentException.
               "Only one passphrase encryptor is supported")))
    (.open
      ^PGPEncryptedDataGenerator
      (reduce
        add-encryption-method!
        (PGPEncryptedDataGenerator.
          (cond->
            (BcPGPDataEncryptorBuilder.
              (tags/symmetric-key-algorithm cipher))
            integrity-check (.setWithIntegrityPacket true)
            random          (.setSecureRandom ^SecureRandom random)))
        encryptors)
      output
      (byte-array (:buffer-size opts 4096)))))


(extend-protocol DataPacket

  PGPEncryptedDataList

  ;; Read through the list of encrypted session keys and attempt to find one
  ;; which the decryptor will unlock. If none are found, the message is not
  ;; decipherable and an exception is thrown.
  (unpack-data
    [data opts]
    (let [content (->> (.getEncryptedDataObjects data)
                       iterator-seq
                       (map #(unpack-data % opts))
                       (remove nil?)
                       first)]
      (when-not content
        (throw (IllegalArgumentException.
                 (str "Cannot decrypt " (pr-str data) " with " (pr-str opts)
                      " (no matching encrypted session key)"))))
      content))


  PGPPBEEncryptedData

  ;; If the decryptor is a string, try to use it to decrypt the passphrase
  ;; protected session key.
  (unpack-data
    [data opts]
    (let [decryptor (:decryptor opts)]
      (when (string? decryptor)
        (->> (BcPBEDataDecryptorFactory.
               (.toCharArray ^String decryptor)
               (BcPGPDigestCalculatorProvider.))
             (.getDataStream data)
             (read-pgp-objects opts)))))


  PGPPublicKeyEncryptedData

  ;; If the decryptor is callable, use it to find a private key matching the id
  ;; on the data packet. Otherwise, use it directly as a private key. If the
  ;; decryptor doesn't match the id, return nil.
  (unpack-data
    [data opts]
    (let [decryptor (:decryptor opts)]
      (when-let [privkey (private-key
                           (if (ifn? decryptor)
                             (decryptor (key-id data))
                             decryptor))]
        (when (= (key-id data) (key-id privkey))
          (->> (BcPublicKeyDataDecryptorFactory. privkey)
               (.getDataStream data)
               (read-pgp-objects opts)))))))



;; ## Constructing PGP Messages

(defn message-output-stream
  "Wraps the given output stream with compression and encryption layers. The
  data will decryptable by the corresponding decryptors. Does _not_ close the
  wrapped stream when it is closed.

  Opts may contain:

  - `:buffer-size` maximum number of bytes per chunk
  - `:compress`    compress the cleartext with the given algorithm, if specified
  - `:cipher`      symmetric key algorithm to use if encryptors are provided
  - `:encryptors`  keys to encrypt the cipher session key with
  - `:armor`       whether to ascii-encode the output

  See `literal-data-stream` and `encrypted-data-stream` for more options."
  ^OutputStream
  [^OutputStream output & opts]
  (let [{:keys [compress cipher encryptors armor]
         :or {cipher :aes-256}
         :as opts}
        (arg-map opts)

        encryptors (arg-coll encryptors)

        wrap-with
        (fn [streams wrapper & args]
          (conj streams (apply wrapper (last streams) args)))

        streams
        (->
          (vector output)
          (cond->
            armor      (wrap-with armored-data-stream)
            encryptors (wrap-with encrypted-data-stream cipher encryptors opts)
            compress   (wrap-with compressed-data-stream compress))
          (wrap-with literal-data-stream opts)
          rest reverse)]
    (proxy [FilterOutputStream] [(first streams)]
      (close []
        (dorun (map #(.close ^OutputStream %) streams))))))


(defn build-message
  "Compresses, encrypts, and encodes the given data and returns an array of
  bytes containing the resulting packet. The data will decryptable by the
  corresponding decryptors.

  See `message-output-stream` for options."
  ^bytes
  [data & opts]
  (let [buffer (ByteArrayOutputStream.)]
    (with-open [^OutputStream stream
                (apply message-output-stream buffer opts)]
      (io/copy data stream))
    (.toByteArray buffer)))


(defn encrypt
  "Constructs a message packet enciphered for the given encryptors. See
  `message-output-stream` for options."
  ^bytes
  [data encryptors & opts]
  (apply build-message data
         :encryptors encryptors
         opts))



;; ## Reading PGP Messages

(defn message-input-stream
  "Wraps the given input stream with decryption and decompression layers.

  Opts may contain:

  - `:decryptor` secret to decipher the message encryption"
  ^InputStream
  [^InputStream input & opts]
  (->> (PGPUtil/getDecoderStream input)
       (read-pgp-objects (arg-map opts))
       flatten
       (map #(ByteBuffer/wrap %))
       bytes/to-input-stream))


(defn read-message
  "Decrypts and decompresses the given data source and returns an array of
  bytes with the decrypted value. See `message-input-stream` for options."
  ^bytes
  [data & opts]
  (let [buffer (ByteArrayOutputStream.)]
    (with-open [^InputStream stream
                (apply message-input-stream
                  (bytes/to-input-stream data)
                  opts)]
      (io/copy stream buffer))
    (.toByteArray buffer)))


(defn decrypt
  "Decrypts a message packet and attempts to decipher it with the given
  decryptor. See `message-input-stream` for options."
  ^bytes
  [data decryptor & opts]
  (apply read-message data
         :decryptor decryptor
         opts))

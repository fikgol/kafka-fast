(ns kafka-clj.fetch-codec
  (:require [clojure.tools.logging :refer [info error]]
            [kafka-clj.buff-utils :refer [write-short-string with-size read-short-string read-byte-array codec-from-attributes]]
            [kafka-clj.codec :refer [uncompress crc32-int]]
            [clj-tuple :refer [tuple]])
  (:import [io.netty.buffer ByteBuf Unpooled PooledByteBufAllocator]
           [io.netty.handler.codec ByteToMessageDecoder ReplayingDecoder]
           [java.util List]
           [java.util.concurrent.atomic AtomicInteger AtomicLong AtomicReference]
           [kafka_clj.util Util FetchStates SafeReplayingDecoder]))

"Provides a ReplayingDecoder implementation for netty,
 The decoder will read fetch responses from a broker, and return either FetchMessage [topic partition bts offset] instances
 or a FetchError [topic partition error-code] for any messages with a error-code > 0"

(defrecord FetchMessage [topic partition ^bytes bts offset locked])
(defrecord FetchError [topic partition error-code])
(defrecord FetchEnd [])

(defn create-fetch-message 
  ([topic partition ^bytes bts offset]
    (create-fetch-message topic partition bts offset true))
  ([topic partition ^bytes bts offset locked]
    (->FetchMessage topic partition bts offset true)))

(defn create-fetch-error [topic partition error-code]
  (->FetchError topic partition error-code))

(declare read-message)

(defn read-message-set [^ByteBuf in]
  "MessageSet => [Offset MessageSize Message]
  Offset => int64
  MessageSize => int32
  Message => Crc MagicByte Attributes Key Value
  Crc => int32
  MagicByte => int8
  Attributes => int8
  Key => bytes
  Value => bytes"
       (let [offset (.readLong in)
             message-size (.readInt in)
             arr (byte-array message-size)
             _ (.readBytes in arr)]
		        {:offset offset
		         :message-size message-size
		         :message (read-message (Unpooled/wrappedBuffer arr))
              ;(read-message in)
           }))

(defn read-messages0 [^ByteBuf buff message-set-size]
  "Read all the messages till the number of bytes message-set-size have been read"
  (let [reader-start (.readerIndex buff)]
		  (loop [msgs []]
		    (if (< (- (.readerIndex buff) reader-start)  message-set-size)
          (recur (conj msgs (read-message-set buff)))
          msgs))))

(defn read-message [^ByteBuf buff]
  "Message => Crc MagicByte Attributes Key Value
  Crc => int32
  MagicByte => int8
  Attributes => int8
  Key => bytes
  Value => bytes"
  (let [crc (Util/unsighedToNumber (.readInt buff))
        start-index (.readerIndex buff) ;start of message + attribytes + magic byte
        magic-byte (.readByte buff)
        attributes (.readByte buff)
        codec (codec-from-attributes attributes)
        key-arr (read-byte-array buff)
        val-arr (read-byte-array buff)
        end-index (.readerIndex buff)
        crc32-arr (byte-array (- end-index start-index))
        crc2 (do (.getBytes buff (int start-index) crc32-arr) (crc32-int crc32-arr))
        ]
    (if (not (= crc2 crc)) 
      (error ">>>>>>>>>>>>>>>>>>>> crc does not match " crc  " crc2 " crc2 " for val " (String. val-arr)))
    ;check attributes, if the message is compressed, uncompress and read messages
    ;else return as is
    (if (> codec 0)
      (let [ ^"[B" ubytes (try (uncompress codec val-arr) (catch Exception e (do   nil)))
             ]
        ;(prn "decompress " codec   " bts " (String. val-arr))
        (if ubytes
          (let [ubuff (Unpooled/wrappedBuffer ubytes)]
	            (doall
			            ;read the messages inside of this compressed message
			            (mapcat flatten (map :message (read-messages0 ubuff (count ubytes))))))))
      (tuple {:crc crc :key key-arr :bts val-arr :crc2 crc2}))))


(defn checkpoint [^SafeReplayingDecoder decoder s]
  (.checkp decoder s))

(defn state [^SafeReplayingDecoder decoder]
  (.getState decoder))



(defn transform [state state-transformers out o in]
  (let [t ((get state-transformers state) out o in)]
    ;(info "from " state " -> " t)
    t))

(defn fetch-response-decoder []
  "
   A handler that reads fetch request responses
   "
  (let [
        topic-len (AtomicInteger. 0)
        partition-len (AtomicInteger. 0)
        message-set-size (AtomicInteger. 0)
        fixed-message-set-size (AtomicLong. 0)
        message-size (AtomicInteger. 0)
        topic-name (AtomicReference. nil)
        partition-name (AtomicReference. nil)
        offset-name (AtomicReference. nil)
        correlation-id-name (AtomicReference. nil)
        initial-state (FetchStates/REQUEST_RESPONSE)
        resp-size (AtomicInteger. 0)
        resp-start-index (AtomicInteger. 0)
        end-of-consume (fn [^List out o ^ByteBuf in]
                         (.add out (->FetchEnd))
                         
                         (let [index (.readerIndex in)
                               diff (- index (.get resp-start-index))
                               size (.get resp-size)]
                           (if (and (> size 0) (< diff size))
                            (do
                              (error "did not consume all bytes: " (- size diff))
                              ;(.skipBytes in (int (- size diff)))
                              )))
                         
                         FetchStates/REQUEST_RESPONSE)
        
        decrement-partition! (fn [] 
                               (if (= (.getAndDecrement partition-len) 1)
                                 (.getAndDecrement topic-len)))
        
        transform-messages (fn [out o in]
                                                       (cond 
                                                            (and (= (.get message-set-size) 0)
                                                                 (= (.get partition-len) 0)
                                                                 (= (.get topic-len) 0))
                                                            (end-of-consume out o in)
                                                       
                                                            (and (= (.get message-set-size) 0)
                                                                 (= (.get partition-len) 0)
                                                                 (> (.get topic-len) 0))
                                                            FetchStates/READ_TOPIC
                                                            (and (= (.get message-set-size) 0)
                                                                 (> (.get partition-len) 0))
                                                            FetchStates/READ_PARTITION
                                                            
                                                            :else FetchStates/READ_MESSAGE_SET)
                                                            
                                                       )
        state-transformers {FetchStates/READ_MESSAGES transform-messages
                            FetchStates/READ_MESSAGE  transform-messages
                            FetchStates/PARTIAL_MESSAGE  transform-messages
                            
                            FetchStates/READ_MESSAGE_SET (fn [out o in]
                                                              (cond 
                                                                   (> (.get message-size) (.get message-set-size))
                                                                     FetchStates/PARTIAL_MESSAGE
                                                                   :else
	                                                                   (let [t (transform-messages out o in)]
	                                                                     (if (= t FetchStates/READ_MESSAGE_SET)
	                                                                            FetchStates/READ_MESSAGE
	                                                                            t))))
                                                       }
        ]
	  (proxy [SafeReplayingDecoder]
	    ;decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out)
	    [initial-state]
	    (decode [ctx ^ByteBuf in ^List out]

	      (let [state (state this)]
         ;(info state " " (.get correlation-id-name)  " [" (.get topic-len) "] " (.get topic-name)  " [" (.get partition-len) "] " (.get partition-name) " " (.get offset-name)) 
	        (cond
	          (= state FetchStates/REQUEST_RESPONSE)
	          (let [size (.readInt in)]
	           (.set resp-size (int size)) 
             (.set resp-start-index (.readerIndex in))
             (checkpoint this FetchStates/RESPONSE))

	          (= state FetchStates/RESPONSE)
	          (let [correlation-id (.readInt in)]
              (.set correlation-id-name correlation-id)
              (checkpoint this FetchStates/FETCH_RESPONSE))

	          (= state FetchStates/FETCH_RESPONSE) ;FetchResponse => [TopicName [Partition ErrorCode HighwaterMarkOffset MessageSetSize MessageSet]]
	          (let [topic-count (.readInt in)]
	            (.set topic-len topic-count)
              (if (= topic-count 0)
                (checkpoint this (end-of-consume out this in))
                (checkpoint this FetchStates/READ_TOPIC)))

            (= state FetchStates/READ_TOPIC)
            (do
              ;(info "READ_TOPIC topic-len " (.get topic-len))
              (if (> (.get topic-len) 0)
		          (let [topic (read-short-string in)
		                partition-count (.readInt in)]
		              (.set partition-len partition-count)
			            (.set topic-name topic)
	                (checkpoint this FetchStates/READ_PARTITION))
              (checkpoint this (end-of-consume out this in))))
                

            (= state FetchStates/READ_PARTITION)
            (if (> (.get partition-len) 0)
              (let [partition (.readInt in)
                  error-code (.readShort in)
                  high-water-mark-offset (.readLong in)]
                (.set partition-name partition)
                (checkpoint this FetchStates/READ_MESSAGES)
                (if (> error-code 0) (.add out (create-fetch-error (.get topic-name) partition error-code))))
              (do
                (checkpoint this FetchStates/READ_TOPIC)))

            (= state FetchStates/PARTIAL_MESSAGE)
            (do 
              ;read partial message and decrement partition
              (info "READ PARTIAL MESSAGE left; " (.get message-set-size))
              (.skipBytes in (int (.get message-set-size)))
              (checkpoint this (transform FetchStates/PARTIAL_MESSAGE state-transformers out this in))
              )
            
            (= state FetchStates/READ_MESSAGES)
            (do
               (.set message-set-size (.readInt in))
               (.set fixed-message-set-size (.get message-set-size))
               
               (if (= (.get message-set-size) 0)
                 (decrement-partition!))
               
               ;(info "message-set-size " (.get message-set-size) " partition-len " (.get partition-len)  " topic-len " (.get topic-len))
               (let [t (transform FetchStates/READ_MESSAGES state-transformers out this in)]
                 ;(info "read-messages to " t)
                 (checkpoint this t)
                 t))

            (= state FetchStates/READ_MESSAGE_SET)
            (let [offset (.readLong in)]

                (.set message-size (.readInt in))
	              (.set offset-name offset)
               
                ;(info "READ_MESSAGE_SET message-size " message-size " offset " offset " message-set-size " (.get message-set-size))
                (.getAndAdd message-set-size -12) ;decrement message set size by 12 bytes
                ;(info "READ_MESSAGE_SET message-set-size -12 " (.get message-set-size))
                
                (if (< (.get message-size) 1)
                  (decrement-partition!))
                  
                (checkpoint this (transform FetchStates/READ_MESSAGE_SET state-transformers out this in))
                ) 
             (= state FetchStates/READ_MESSAGE)
             (let [reader-start (.readerIndex in)
                   messages (read-message in)]
               
               ;(info "reading message bytes")
               (doseq [{:keys [^"[B" bts]} messages]
                 (.add out (create-fetch-message (.get topic-name) (.get partition-name) bts (.get offset-name))))
               
               ;(info "complete read message bytes")
               (let [bts-read (- (.readerIndex in) reader-start)]
                 (.getAndAdd  message-set-size (* -1 bts-read))
                 
                 (if (< (.get message-set-size) 1)
                   (decrement-partition!))
                 
                 ;(info "calling checkpoint ")
                 (let [t (transform FetchStates/READ_MESSAGE state-transformers out this in)]
                   ;(info "checkpoint " t)
                   (checkpoint this t))
                   

                     ))
             :else 
                (throw (RuntimeException. (str "The state " state " is not supported")))


	        ))))))


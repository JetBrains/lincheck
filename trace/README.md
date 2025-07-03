# Trace Recoding Format & API.
## Trace recording on-disk format.
 Trace is stored on disk in binary format which allows some limited form of streaming.

 Stored trace consists of two files: the main data file and and auxiliary *index* file.

 The main data file can be used without an index, as the index will be re-created in memory if a data file
without an index or with a broken index is opened. For now, the re-created index is not stored back to disk.

 Index is not used for batch (not lazy) loading of trace at all.

### Data format.
 Data file consists of a *header*, series of *objects* which are divided into *data blocks*
and end-of-file marker.

 All multibyte values are written in format defined by `DataOuput` JVM class.
Strings are written with `DataOutput.writeUTF()` method.

#### Header
 Header is 16 bytes. The first 8 bytes is a *magic* constant which identifies a file 
as the trace data file, and next 8 bytes is version. Version doesn't have any specific
meaning or fields (like semantic versioning or alike), but it is increased on each format
change. No backward compatibility is provided (yet), so code which read data file must
use exactly the same version as code which wrote it.

#### Object kinds.
 Each possible object in the data file (and index file, for the matter) has 
its own *kind*. It is like class or type. Even data block headers and footers
are objects with their specific kinds. Kind is always taking 1 byte in binary
format. Each object representation starts from its kind, and each record in the index
has a corresponding kind.

 Object descriptions will omit this first byte which stores kind in their layout
descriptions, for brevity.

#### Data blocks.
 Data blocks contain of a variable number of *objects*. Data block always contains
objects written by a single thread of execution. Data blocks are formed in memory
by threads and written to disk atomically together with index for all objects
that fit into a given data block.

 You can think about data objects as a way to organize separate data files, one per
execution thread, in one file system file.

 One block of data contains trace points only from one and only thread.

 Other objects are thread-neutral and can be placed in any data block.

 Each data block starts from object `BLOCK_START` which consists of one field: 4 byte
integer, which corresponds to `threadId` field of trace points. 

 After this, all other objects follows, and last object in the block is `BLOCK_END` which
doesn't have any data fields.

 Data blocks don't have any size limitations.

 As trace is written in «streaming» fashion, and it is impossible to know the size of
the block ahead of time sometimes, or where the next block from the same thread will be
placed, there is no linkage between blocks in the data file.

 Write of data block is the point of synchronization between threads, so the size of the
data block is chosen to avoid contention at this point.

 Price for a rather large data block is the possibility to lose a lot of information in case
of a program crash, as all trace objects accumulated in memory will be lost.

#### Objects.
 Objects are parts of the recorded trace and some additional bookkeeping data.

 There are two ways to categorize objects.

 The first subdividing is between *atomic* and *container* objects.

- *Atomic objects* are... atomic! They cannot be split between data blocks. As they are not
  split, they always occupy a continuous range of bytes in a physical data file and can be read
  without referring to block structure as soon as a physical offset of their start is known.
  They are written as *kind* byte followed by all their data fields.
- *Container objects* can be split between data blocks and represented as two on-disk objects:
  one for the fixed header and one for fixed footer. Between these two on-disk objects the content of
  the container is written, as a series of corresponding objects. Header and footer of a container
  object can be written to different data blocks belonging to the same thread. They could be separated
  by any number of data blocks. Now there is only one container object: a method call trace point.

 The second subdivision is between *thread-neutral* and *thread-dependent* objects.

- *Thread-neutral* objects are shared between al threads. It is all reference objects, which
  are used by all threads. All these objects are *atomic*. They are written to the data file
  and index by the first thread which encounters them. This could lead to rare duplication
  of these objects in the data file, but it doesn't lead to conflicts, as all these objects
  are *immutable*.
- *Thread-dependent* objects are created by specific threads and belong to them. These objects must be
  stored in a data block which has the same thread id as they. Now it is only trace points.

 Container objects are always thread-dependent.

 Data block can be seen as a container thread-dependent object itself, but as a data block cannot
be placed into a data block, it is not exactly an object, though it follows rules for container objects.

 There will not be an exhaustive list of all supported objects, as it is not assumed that trace
will be loaded without usage of the provided API, and details can be seen in this API.

 All thread-neutral objects belong to *the context* of the trace; it is like reference data 
shared by all trace points from different threads.

 All thread-dependent objects are *roots* or belong to some container node with the same 
owner thread.

 Roots are read first when a trace is opened.

 All container nodes are thread-dependent for now.

#### Objects dependencies.
 Each object can be written into the data file only after all its dependencies. If an object refers to
another object by id (like `MethodDescriptor` refers to `ClassDescriptor` by id or any trace point
refers to `CodeLocation` by id), a referred object must be already written to file, maybe by another
thread. It could lead to a situation when a referred object is put into file physically later than
referring, but it is supported by deserialization code.

 Container objects own all thread-dependent objects that were written between the container
header on-disk object and the footer on-disk object. Container objects can be nested without 
theoretical limit for nesting and can contain up to `Int.MAX_VALUE` direct children.

#### Trace points.
 Trace points are the main content of recorded trace. All other objects only support and reference
information for trace points.

 All tracepoints share one kind `TRACEPOINT`.

 There are two kinds of tracepoints: leaf and container.

 There is only one container trace point (and it is the only container object in format for now):
method call tracepoint.

 Leaf tracepoints are simple thread-dependent atomic objects. They cannot be split between data
blocks, as any atomic objects and typically consists of several integer fields which refer
thread-independent objects, like `FiledDescriptor` or `CodeLocation`.

 Method call tracepoint is split into two objects: `TRACEPOINT` for header, which contains all
information available at the moment of corresponding method call and `TRACEPOINT_FOOTER` which
marks the end of trace point's children list (container content) and also stores a result of method call.

 Method call tracepoints can be nested, of course.
 
 To quickly skip all call's children, an index is used.

 All trace points but method call ones are always loaded fully. Method call tracepoints
can be loaded without children at all, with one level of children, or deeply (greedy).

 Also, API provides a way to load all or some of the children for method call tracepoint
which was loaded without them. For this method called tracepoint remembers the locations 
of all its children; these locations are filled when trace point is loaded. These offsets
are *logical* (see below) and converted to physical ones with the help of thread id.

### Index format.
 Index consists from *header*, number of fixed-sized records (*cells*) and *footer*.

#### Header.
 Header is 16 bytes. The first 8 bytes is a *magic* constant which identifies a file
as the trace index file, it is distinct from data files' *magic*, and the next 8 bytes are
the same version as in the data file.

#### Footer.
 Footer is cell for object kind `EOF` and 8 byte *magic* repeated to be sure that `EOF`
cell is not coincidence.

#### Cells
 Each cell consists of 4 fields:
- **Object kind** — 1 byte.
- **Object id** — 4 bytes (`Int`).
- **Start offset** — 8 bytes (`Long`).
- **End offset** — 8 bytes (`Long`).

 Most object kinds don't use end offset and fills it with `-1`.

 Index contains cells for all thread-independent objects, data blocks, and container objects.

 Thread-dependent atomic objects (i.e. leaf trace points) are not recorded to index, as they
can be loaded only as part of their parent's content. 

- For *data blocks*, the **start offset** points to the beginning of corresponding `BLOCK_START`
  «object» in the data file and **end offset** points to the beginning of corresponding `BLOCK_END`
  «object». Both offsets are *physical* offsets in the data file and don't require any translation.

- For all *thread-independent atomic objects*, the **start offset** is a *physical* offset in
  the data file. These offsets point to places from which corresponding object, starting with
  object kind, can be read without additional calculations. API checks consistency between
  index and data file and reports an error when the kind or id of the object in the data file 
  is not equal to the corresponding kind and/or id in the index cell. In such cases the index
  is thought to be broken and discarded. **End offset** is not used for such objects and ignored.

- For containers (which are all thread-dependent now) the **start offset** points right
  after a fixed header object of the container (to the first byte of its variable-size content).
  **End offset** points to last byte of content, right before `TRACEPOINT_FOOTER` on-disk object.
  Both of these offsets are *logical*, as opposed to *physical* offsets of other cells. It means
  that these offsets point to the data stream of a corresponding thread and must be translated to
  physical offsets in the data file according to data blocks for a given thread. Offset 0 is
  the first data byte (byte after block header!) in the first data block for a given thread.
  Offset which equals to data size in the first data block is the first data byte in the second
  data block for the same thread. It is maybe physically thousands of bytes away from the physical
  offset of the previous data byte.

## API for trace-recording writing and reading.
 There are two API to write (save) trace and two API to read (load) it.

### API to write (save) trace. 
 Trace Recorder can collect trace points completely in memory, from start to finish, and
then write (save) all data into a data file and index in one go.

 Or Trace Recorder can write trace points as soon as they are created by event callbacks and
store only the current call stack in the memory.

 The first («collect-and-dump») method has fewer synchronization points for multiple threads,
as each thread collects its own trace independently of other threads. Only reference
data storage becomes points of synchronization. But it requires a lot of memory for any
somewhat complex program and could be slow due to GC pressure and even lead to OOMs. Also,
if the program crashes, no trace could be recovered.

 The second («streaming») method has more synchronization points, as writing blocks of
data and parts of index become point when threads meet each other. Also, tracking of
saved thread-independent objects can become a point of contention. On the other hand,
memory pressure in this scenario is fixed (each thread has a constant-size buffer for 
accumulating data), and in the case of a small number of threads, it becomes much faster.
Trace recovering in case of the program crash is possible too, though all data accumulated in
memory after the last save is lost anyway and saving is triggered only by the number of data 
accumulated, not by timer.

 All callback methods are shared between methods, and selection is performed by using one of
the two strategies, which hooks up into trace point creation and method call result processing.

 Both strategies use respectable implementations of `TraceWriter` interface which extends
`DataOutput` and allows all other code to write objects in consistent way, but with different
 backends.

 Each trace point implements methods to save al dependencies and itself to any `TraceWriter`
and these methods are called by strategies and support code. Method call trace point also
has a method to save footer after all children are saved. Saving of chidlren tracepoints
is controlled by strategy's code too, method call tracepoint doesn't save its onw children.

#### Collect-and-dump.
 This strategy simply creates a per-thread tree of trace points. It doesn't store it to file, 
and a separate function must be used to create data and index files.
 
 The function to save trees of trace points is inherently single-threaded. It uses
a straightforward way to track thread-independent objects and creates one data block
per one thread, without interleaving trace points from different threads.

#### Streaming.
 This strategy saves trace points as soon as they are created and doesn't store them
in memory, except the current call stack consisting of method call trace points.

 Each thread has its own instance of `TraceWriter` implementation. This implementation
stores all data in the memory buffer and flushes this buffer when it is almost full. Index
cells are saved into the simple list and flushed together with a data buffer.

 Strategy, which is the owner for all per-thread `TraceWriter` instances, manage one data
file and one index file and use standard JVM synchronization to avoid conflicts
and non-atomic flushes.

 This strategy effectively splits data from each thread into almost-1MiB (cannot be configured
without changing sources for now) data blocks.

### API to read (load) trace.
 Trace can be loaded in memory completely or lazily.
 
 Eager loader reads whole trace points with all thread-independent objects in one go, 
reading a data file once from beginning to end. It allows work with trace as with
a forest of objects (one tree per thread), but can consume a huge amount of memory.

 Lazy loader loads all thread-independent objects first, using index to locate them, 
and only top-level method call trace point («root») for each thread after that.

 Now all code uses global context (collection of thread-independent objects), so
no two different traces could be loaded simultaneously. Each next call for
loading API, no matter eager or lazy, invalidates the previously loaded trace. It will
be changed in the future to allow loading of multiple traces. High-level API is ready
for this change, but trace point implementations are not.

#### Eager.
 Eager loader (function `loadRecordedTrace`) doesn't need index as it loads a full
data file in any case. It can read data from any `InoutStream` and returns
re-created context (which contains all thread-independent reference objects) and list
of method call trace points, one per thread.

 All children of all method call tracepoints are loaded and linked to their parents,
in full available depth.

 Beware that this data structure can consume a humongous amount of memory.

 Now returned context is global `TRACE_CONTEXT`, but it will change in the future
to allow loading multiple traces in once.

#### Lazy.
 Lazy loader (class `LazyTraceReader`) requires file name and optional index `InputStream`
as arguments. It cannot use any `InputStream` for data input as it needs effective
seekable stream to support random access to data.

 Lazy loader first loads or re-creates index, populates context with all thread-independent
reference objects, and then loads root method call trace points, one per thread.

 These method call trace points don't have their children loaded, thay only know
where to load children and their count.

 After that the same insance of lazy loader can be used to load any children of any
already loaded method call tracepoint. It is possible to load all children at once
or load only some range of children.

 Method call trace point with children which were not yet loaded will have `null`
at corresponding positions in the children list (`events` property). This list
will have a proper fixed number of elements from the beginning.

 Also, method call trace point which is loaded in such way can «forget» any children
or all of them, but such children can be loaded again later with the help of the same
lazy loader.

 If the index cannot be loaded due to absence, any I/O error, or inconsistency with
the data file, lazy loader reads the whole data file first to re-create context,
data blocks, and method call trace points indices. After that it works as in
the case with a healthy index. This recreation can take a lot of time.

 For now there is no way to re-create the index on disk, but this feature is planned
for the future.
 
 Instance of lazy loader contains a reference to context used to load data, but, again,
for now it is global `TRACE_CONTEXT` and using another instance of lazy or eager loader will
invalidate old one. It will change in the future.

### Printing a tree of trace points.
 Tree of trace points can be pretty-printed to any print stream with help
of `printRecorderTrace` function. It prints text representation of each trace point
in the provided list, with indentations to show call hierarchy.
 
 All not loaded tracepoints in method call tracepoints are folded to
lines indicating how much was missed.

 Tracepoints can be printed with their code locations (`verbose = true`) or 
without (`verbose = false`).

 If `null` is passed as output filename, `System.out` is used as output stream.

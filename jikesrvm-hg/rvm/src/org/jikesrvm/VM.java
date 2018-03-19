/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm;

import org.jam.driver.net.NapiManager;
import org.jam.driver.serial.PcBootSerialPort;
import org.jam.driver.serial.SerialPortBaudRate;
import org.jam.net.ethernet.Ethernet;
import org.jam.net.ethernet.EthernetAddr;
import org.jam.net.inet4.Arp;
import org.jam.net.inet4.InetAddress;
import org.jam.tests.EchoClient;
import org.jam.tests.LdivTests;
import org.jam.tests.Sleep;

import java.net.SocketException;
import java.net.UnknownHostException;

import org.jam.board.pc.I8259A;
import org.jam.board.pc.IMCR;
import org.jam.board.pc.Platform;
import org.jikesrvm.ArchitectureSpecific.ThreadLocalState;
import org.jikesrvm.adaptive.controller.Controller;
import org.jikesrvm.adaptive.util.CompilerAdvice;
import org.jikesrvm.classloader.Atom;
import org.jikesrvm.classloader.BootstrapClassLoader;
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.classloader.RVMClassLoader;
import org.jikesrvm.classloader.RVMMember;
import org.jikesrvm.classloader.MemberReference;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.classloader.TypeReference;
import org.jikesrvm.compilers.baseline.BaselineCompiler;
import org.jikesrvm.compilers.common.BootImageCompiler;
import org.jikesrvm.compilers.common.RuntimeCompiler;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.mm.mminterface.Selected;
import org.jikesrvm.runtime.ArchEntrypoints;
import org.jikesrvm.runtime.BootRecord;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.runtime.ExitStatus;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.runtime.RuntimeEntrypoints;
import org.jikesrvm.scheduler.IdleThread;
import org.jikesrvm.scheduler.Lock;
import org.jikesrvm.scheduler.MainThread;
import org.jikesrvm.scheduler.Synchronization;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.scheduler.TestThread;
import org.jikesrvm.tuningfork.TraceEngine;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UninterruptibleNoWarn;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.pragma.UnpreemptibleNoWarn;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Extent;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

import test.org.jikesrvm.basic.core.bytecode.TestArithmetic;

/**
 * A virtual machine.
 */
@Uninterruptible
public class VM extends Properties implements Constants, ExitStatus {

  /**
   * Reference to the main thread that is the first none VM thread run
   */
  public static MainThread mainThread;

  //----------------------------------------------------------------------//
  //                          Initialization.                             //
  //----------------------------------------------------------------------//

  /**
   * Prepare VM classes for use by boot image writer.
   * @param classPath class path to be used by RVMClassLoader
   * @param bootCompilerArgs command line arguments for the bootimage compiler
   */
  @Interruptible
  public static void initForBootImageWriter(String classPath, String[] bootCompilerArgs) {
    if (VM.VerifyAssertions) VM._assert(!VM.runningVM);
    if (VM.VerifyAssertions) VM._assert(!VM.runningTool);
    writingBootImage = true;
    init(classPath, bootCompilerArgs);
  }

  /**
   * Prepare VM classes for use by tools.
   */
  @Interruptible
  public static void initForTool() {
    initForTool(System.getProperty("java.class.path"));
  }

  /**
   * Prepare VM classes for use by tools.
   * @param classpath class path to be used by RVMClassLoader
   */
  @Interruptible
  public static void initForTool(String classpath) {
    if (VM.VerifyAssertions) VM._assert(!VM.runningVM);
    if (VM.VerifyAssertions) VM._assert(!VM.writingBootImage);
    runningTool = true;
    init(classpath, null);
  }

  
  /**
   * Begin VM execution.<p>
   *
   * Uninterruptible because we are not setup to execute a yieldpoint
   * or stackoverflow check in the prologue this early in booting.<p>
   *
   * The following machine registers are set by "C" bootstrap program
   * before calling this method:
   * <ol>
   *   <li>JTOC_POINTER - required for accessing globals
   *   <li>FRAME_POINTER - required for accessing locals
   *   <li>THREAD_ID_REGISTER - required for method prolog (stack overflow check)
   * </ol>
   */
  @UnpreemptibleNoWarn("No point threading until threading is booted")
  @Entrypoint
  public static void boot() {
    writingBootImage = false;
    runningVM = true;
    verboseBoot = 1; // BootRecord.the_boot_record.verboseBoot;
    ThreadLocalState.setCurrentThread(RVMThread.bootThread);
    /*
     * Setup the serial port
     */
    PcBootSerialPort.setBaudRate(SerialPortBaudRate.BAUDRATE_115200);
    PcBootSerialPort.setParityNone();
    PcBootSerialPort.setWordLength8();
    PcBootSerialPort.setStopBits1();
    sysWriteLockOffset = Entrypoints.sysWriteLockField.getOffset();
    // Set up the current RVMThread object.  The bootstrap program
    // has placed a pointer to the current RVMThread in a special
    // register.
    if (verboseBoot >= 1) VM.sysWriteln("Setting up current RVMThread");
    ThreadLocalState.boot();

    // Finish thread initialization that couldn't be done in boot image.
    // The "stackLimit" must be set before any interruptible methods are called
    // because it's accessed by compiler-generated stack overflow checks.
    //
    if (verboseBoot >= 1) VM.sysWriteln("Doing thread initialization");
    RVMThread currentThread = RVMThread.getCurrentThread();
    currentThread.stackLimit = Magic.objectAsAddress(currentThread.getStack()).plus(ArchitectureSpecific.StackframeLayoutConstants.STACK_SIZE_GUARD);
    if (verboseBoot >= 1)
   	{
    	VM.sysWrite("Stack Limit: "); VM.sysWrite(Magic.objectAsAddress(currentThread.getStack())); VM.sysWriteln(" ", currentThread.stackLimit);
   	}

    finishBooting();
  }

/**
   * Complete the task of booting Jikes RVM.
   * Done in a secondary method mainly because this code
   * doesn't have to be uninterruptible and this is the cleanest
   * way to make that distinction.
   */
  @Interruptible
  private static void finishBooting() {
    // fixme: should be configurable
    RVMThread.availableProcessors = 1;

    // Set up buffer locks used by Thread for logging and status dumping.
    //    This can happen at any point before we start running
    //    multi-threaded.
    Services.boot();

    /*
     * Baremetal memory initialization
     */
    org.jam.mm.MemoryManager.boot(BootRecord.the_boot_record);
    
    // Initialize memory manager.
    //    This must happen before any uses of "new".
    //
    if (verboseBoot >= 1) {
      VM.sysWriteln("Setting up memory manager: bootThread = ", Magic.objectAsAddress(RVMThread.bootThread));
    }
    MemoryManager.boot(BootRecord.the_boot_record);

    // Reset the options for the baseline compiler to avoid carrying
    // them over from bootimage writing time.
    //
    if (verboseBoot >= 1) VM.sysWriteln("Initializing baseline compiler options to defaults");
//    BaselineCompiler.initOptions();

    // Fetch arguments from program command line.
    //
    // Early initialization of TuningFork tracing engine.
    TraceEngine.engine.earlyStageBooting();

    // Allow Memory Manager to respond to its command line arguments
    //
    if (verboseBoot >= 1) VM.sysWriteln("Collector processing rest of boot options");
    MemoryManager.postBoot();

    // Initialize class loader.
    //
    String bootstrapClasses = ""; // CommandLineArgs.getBootstrapClasses();
    if (verboseBoot >= 1) VM.sysWriteln("Initializing bootstrap class loader: ", bootstrapClasses);
    RVMClassLoader.boot();      // Wipe out cached application class loader

    // Initialize statics that couldn't be placed in bootimage, either
    // because they refer to external state (open files), or because they
    // appear in fields that are unique to Jikes RVM implementation of
    // standard class library (not part of standard JDK).
    // We discover the latter by observing "host has no field" and
    // "object not part of bootimage" messages printed out by bootimage
    // writer.
    //
    if (verboseBoot >= 1) VM.sysWriteln("Running various class initializers");

    if (VM.BuildForGnuClasspath) {
      runClassInitializer("java.util.WeakHashMap"); // Need for ThreadLocal
    }
    runClassInitializer("org.jikesrvm.classloader.Atom$InternedStrings");

    if (VM.BuildForGnuClasspath) {
      runClassInitializer("gnu.classpath.SystemProperties");
      runClassInitializer("java.lang.Throwable$StaticData");
    }

    runClassInitializer("java.lang.Runtime");
    runClassInitializer("java.lang.System");
    runClassInitializer("sun.misc.Unsafe");

    runClassInitializer("java.lang.Character");
    runClassInitializer("org.jikesrvm.classloader.TypeReferenceVector");
    runClassInitializer("org.jikesrvm.classloader.MethodVector");
    runClassInitializer("org.jikesrvm.classloader.FieldVector");
    // Turn off security checks; about to hit EncodingManager.
    // Commented out because we haven't incorporated this into the CVS head
    // yet.
    // java.security.JikesRVMSupport.turnOffChecks();
    if (VM.BuildForGnuClasspath) {
      runClassInitializer("java.lang.ThreadGroup");
    }
    /* We can safely allocate a java.lang.Thread now.  The boot
       thread (running right now, as a Thread) has to become a full-fledged
       Thread, since we're about to encounter a security check:

       EncodingManager checks a system property,
        which means that the permissions checks have to be working,
        which means that VMAccessController will be invoked,
        which means that ThreadLocal.get() will be called,
        which calls Thread.getCurrentThread().

        So the boot Thread needs to be associated with a real Thread for
        Thread.getCurrentThread() to return. */
    VM.safeToAllocateJavaThread = true;

    if (VM.BuildForGnuClasspath) {
      runClassInitializer("java.lang.ThreadLocal");
      runClassInitializer("java.lang.ThreadLocalMap");
    }
    // Possibly fix VMAccessController's contexts and inGetContext fields
    if (VM.BuildForGnuClasspath) {
      runClassInitializer("java.security.VMAccessController");
    }
    if (VM.BuildForHarmony) {
      runClassInitializer("java.security.AccessController");
    }
    if (verboseBoot >= 1) VM.sysWriteln("Booting Lock");
    Lock.boot();

    // Enable multiprocessing.
    // Among other things, after this returns, GC and dynamic class loading are enabled.
    //
    if (verboseBoot >= 1) VM.sysWriteln("Booting scheduler");
    RVMThread.boot();

    if (verboseBoot >= 1) VM.sysWriteln("Setting up boot thread");
    RVMThread.getCurrentThread().setupBootJavaThread();

    runClassInitializer("java.lang.String");
//    runClassInitializer("java.io.File"); // needed for when we initialize the
    // system/application class loader.
    if (VM.BuildForGnuClasspath) {
      runClassInitializer("gnu.java.security.provider.DefaultPolicy");
    }
    runClassInitializer("java.net.URL"); // needed for URLClassLoader
    /* Needed for ApplicationClassLoader, which in turn is needed by
       VMClassLoader.getSystemClassLoader()  */
    if (VM.BuildForGnuClasspath) {
      runClassInitializer("java.net.URLClassLoader");
    }
    /* Used if we start up Jikes RVM with the -jar argument; that argument
     * means that we need a working -jar before we can return an
     * Application Class Loader. */
    runClassInitializer("java.net.URLConnection");
    if (VM.BuildForGnuClasspath) {
      runClassInitializer("gnu.java.net.protocol.jar.Connection$JarFileCache");
      runClassInitializer("java.lang.ClassLoader$StaticData");
    }
    runClassInitializer("java.lang.Class$StaticData");

    runClassInitializer("java.nio.charset.Charset");
    if (VM.BuildForGnuClasspath) {
      runClassInitializer("java.nio.charset.CharsetEncoder");
    }
    runClassInitializer("java.nio.charset.CoderResult");
    if (VM.BuildForHarmony) {
      runClassInitializer("org.apache.harmony.niochar.CharsetProviderImpl");
    }

    runClassInitializer("java.io.PrintWriter"); // Uses System.getProperty
    System.setProperty("line.separator", "\n");
    runClassInitializer("java.io.PrintStream"); // Uses System.getProperty
    runClassInitializer("java.util.Locale");
    runClassInitializer("java.util.ResourceBundle");
    // Run class initializers that require JNI
    if (verboseBoot >= 1) VM.sysWriteln("Running late class initializers");
    runClassInitializer("java.lang.Math");
    runClassInitializer("java.util.TreeMap");
    if (VM.BuildForGnuClasspath) {
//      runClassInitializer("gnu.java.nio.VMChannel");
//      runClassInitializer("gnu.java.nio.FileChannelImpl");
    }
    runClassInitializer("java.io.FileDescriptor");
    if (VM.BuildForGnuClasspath) {
      runClassInitializer("java.lang.VMDouble");
    }
    runClassInitializer("java.math.BigInteger");
    runClassInitializer("java.util.PropertyPermission");
    runClassInitializer("org.jikesrvm.classloader.RVMAnnotation");
    runClassInitializer("java.lang.annotation.RetentionPolicy");
    runClassInitializer("java.lang.annotation.ElementType");
    runClassInitializer("java.lang.Thread$State");
    if (VM.BuildForGnuClasspath) {
      runClassInitializer("java.lang.VMClassLoader");
    }

    if (verboseBoot >= 1) VM.sysWriteln("initializing standard streams");
    // Initialize java.lang.System.out, java.lang.System.err, java.lang.System.in
    // FileSystem.initializeStandardStreams();

    ///////////////////////////////////////////////////////////////
    // The VM is now fully booted.                               //
    // By this we mean that we can execute arbitrary Java code.  //
    ///////////////////////////////////////////////////////////////
    if (verboseBoot >= 1) VM.sysWriteln("VM is now fully booted");

    // Inform interested subsystems that VM is fully booted.
    VM.fullyBooted = true;
    MemoryManager.fullyBootedVM();
    BaselineCompiler.fullyBootedVM();
//    TraceEngine.engine.fullyBootedVM();

    runClassInitializer("java.util.logging.Level");
    if (VM.BuildForGnuClasspath) {
      runClassInitializer("gnu.java.nio.charset.EncodingHelper");
//      runClassInitializer("java.lang.reflect.Proxy");
//      runClassInitializer("java.lang.reflect.Proxy$ProxySignature");
    }
//    runClassInitializer("org.jam.board.pc.PciCapId");
    
    // runClassInitializer("java.util.logging.Logger");
    // Initialize compiler that compiles dynamically loaded classes.
    //
    if (verboseBoot >= 1) VM.sysWriteln("Initializing runtime compiler");
    RuntimeCompiler.boot();

    if (VM.verboseClassLoading || verboseBoot >= 1) VM.sysWrite("[VM booted]\n");

    if (VM.BuildForAdaptiveSystem) {
      if (verboseBoot >= 1) VM.sysWriteln("Initializing adaptive system");
      Controller.boot();
    }

    if (verboseBoot >= 1) VM.sysWriteln("Initializing Application Class Loader");
    RVMClassLoader.getApplicationClassLoader();
    RVMClassLoader.declareApplicationClassLoaderIsReady();

    if (verboseBoot >= 1) {
      VM.sysWriteln("Turning back on security checks.  Letting people see the ApplicationClassLoader.");
    }
    // Turn on security checks again.
    // Commented out because we haven't incorporated this into the main CVS
    // tree yet.
    // java.security.JikesRVMSupport.fullyBootedVM();

    VM.sysWriteln("contextRegister offset ", Entrypoints.threadContextRegistersField.getOffset());
    VM.sysWriteln("gprs offset ", ArchEntrypoints.registersGPRsField.getOffset());
    VM.sysWriteln("sp offset ", Entrypoints.stackPointerField.getOffset());
    /*
     * Need to initialize the platform here because
     * we need the scheduler to start threads.
     */
    Platform.boot();
    
    // Put the IdleThread on the queue
    if(verboseBoot >= 1)
    {
        VM.sysWriteln("Idle Thread");
    }
    new IdleThread().start();
    if (VM.BuildForAdaptiveSystem) {
      CompilerAdvice.postBoot();
    }

    System.setOut(Platform.serialPort.getPrintStream());
//     System.out.println("System.out is working!");
    
    if (verboseBoot >= 1)
    {
        VM.sysWrite("Enabling GC: ");
        VM.sysWriteln(Selected.name);
    }
    MemoryManager.enableCollection();

//    LdivTests.test1();
    // Schedule "main" thread for execution.
    if (verboseBoot >= 2) VM.sysWriteln("Creating main thread");
    // Create main thread.
    if (verboseBoot >= 1) VM.sysWriteln("Constructing mainThread");
//    mainThread = new MainThread(null);
//  mainThread.start();
    Magic.enableInterrupts();
    
    /*
     * Send an arp request
     */
//    InetAddress senderIp = new InetAddress(192, 168, 100, 2);
//    InetAddress targetIp = new InetAddress(192, 168, 100, 1);
//    Arp arp = new Arp(Platform.net.getEthernetAddress(), senderIp, targetIp);
//    arp.request();
//    Ethernet arpRequest = new Ethernet(EthernetAddr.BROADCAST_ADDRESS, arp);
//    Platform.net.transmit(arpRequest);
//    Platform.net.receive();
    /*
     * Sleep test
     */
//    Sleep sleep = new Sleep();
//    new Thread(sleep).start();
    
    Thread napiThread = new Thread(new NapiManager());
    napiThread.setName("NAPI Manager");
    napiThread.start();
    // Schedule "main" thread for execution.
    if (verboseBoot >= 1) VM.sysWriteln("Starting main thread");
    try
    {
        Thread echoTest = new Thread(new EchoClient());
        echoTest.start();
    } catch (SocketException e)
    {
        e.printStackTrace();
    } catch (UnknownHostException e)
    {
        e.printStackTrace();
    }
    /*
     * Exhaust class test
     */
//    runClassInitializer("test.org.jikesrvm.basic.core.threads.XThread");
//    Thread testThread = new TestThread();
//    testThread.start();
//    VM.sysWriteln("Main thread started");
    // terminate boot thread
    RVMThread.getCurrentThread().terminate();    
    // Say good bye to the boot thread
    Magic.enableInterrupts();
//    Platform.masterPic.setInterrupt(I8259A.COM1);
//    Platform.masterPic.setInterrupt(I8259A.SYSTEM_TIMER);
//    IMCR.enableIRQS();
    Magic.yield();
    VM.shutdown(1);
    VM.sysWriteln("Boot thread has been resurrected! This is bad!!!");
    // As one suspects this should never be reached
    if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
  }

  /**
   * Run {@code <clinit>} method of specified class, if that class appears
   * in bootimage and actually has a clinit method (we are flexible to
   * allow one list of classes to work with different bootimages and
   * different version of classpath (eg 0.05 vs. cvs head).
   * <p>
   * This method is called only while the VM boots.
   *
   * @param className
   */
  @Interruptible
  static void runClassInitializer(String className) {
    if (verboseBoot >= 2) {
      sysWrite("running class intializer for ");
      sysWriteln(className);
    }
    Atom classDescriptor = Atom.findOrCreateAsciiAtom(className.replace('.', '/')).descriptorFromClassName();
    TypeReference tRef =
        TypeReference.findOrCreate(BootstrapClassLoader.getBootstrapClassLoader(), classDescriptor);
    RVMClass cls = (RVMClass) tRef.peekType();
    if (null == cls) {
      sysWrite("Failed to run class intializer for ");
      sysWrite(className);
      sysWriteln(" as the class does not exist.");
    } else if (!cls.isInBootImage()) {
      sysWrite("Failed to run class intializer for ");
      sysWrite(className);
      sysWriteln(" as the class is not in the boot image.");
    } else {
      RVMMethod clinit = cls.getClassInitializerMethod();
      if (clinit != null) {
    	clinit.compile();
        if (verboseBoot >= 10) {
        	VM.sysWrite("Invoking <init> for ");
        	VM.sysWriteln(className);
        }
        try {
          Magic.invokeClassInitializer(clinit.getCurrentEntryCodeArray());
        } catch (Error e) {
          throw e;
        } catch (Throwable t) {
          ExceptionInInitializerError eieio =
              new ExceptionInInitializerError(t);
          throw eieio;
        }
        // <clinit> is no longer needed: reclaim space by removing references to it
        clinit.invalidateCompiledMethod(clinit.getCurrentCompiledMethod());
      } else {
        if (verboseBoot >= 10) VM.sysWriteln("has no clinit method ");
      }
      cls.setAllFinalStaticJTOCEntries();
    }
  }

  //----------------------------------------------------------------------//
  //                         Execution environment.                       //
  //----------------------------------------------------------------------//

  /**
   * Verify a runtime assertion (die w/traceback if assertion fails).<p>
   *
   * Note: code your assertion checks as
   * {@code if (VM.VerifyAssertions) VM._assert(xxx);}
   * @param b the assertion to verify
   */
  @Inline(value=Inline.When.AllArgumentsAreConstant)
  public static void _assert(boolean b) {
    _assert(b, null, null);
  }

  /**
   * Verify a runtime assertion (die w/message and traceback if
   * assertion fails).<p>
   *
   * Note: code your assertion checks as
   * {@code if (VM.VerifyAssertions) VM._assert(xxx);}
   *
   * @param b the assertion to verify
   * @param message the message to print if the assertion is false
   */
  @Inline(value=Inline.When.ArgumentsAreConstant, arguments={0})
  public static void _assert(boolean b, String message) {
    _assert(b, message, null);
  }

  @Inline(value=Inline.When.ArgumentsAreConstant, arguments={0})
  public static void _assert(boolean b, String msg1, String msg2) {
    if (!VM.VerifyAssertions) {
      sysWriteln("vm: somebody forgot to conditionalize their call to assert with");
      sysWriteln("vm: if (VM.VerifyAssertions)");
      _assertionFailure("vm internal error: assert called when !VM.VerifyAssertions", null);
    }
    if (!b) _assertionFailure(msg1, msg2);
  }

  @NoInline
  @UninterruptibleNoWarn("Interruptible code not reachable at runtime")
  private static void _assertionFailure(String msg1, String msg2) {
    if (msg1 == null && msg2 == null) {
      msg1 = "vm internal error at:";
    }
    if (msg2 == null) {
      msg2 = msg1;
      msg1 = null;
    }
    if (VM.runningVM) {
      if (msg1 != null) {
        sysWrite(msg1);
      }
      sysFail(msg2);
    }
    throw new RuntimeException((msg1 != null ? msg1 : "") + msg2);
  }

  /**
   * Format a 32 bit number as "0x" followed by 8 hex digits.
   * Do this without referencing Integer or Character classes,
   * in order to avoid dynamic linking.
   * TODO: move this method to Services.
   * @param number
   * @return a String with the hex representation of the integer
   */
  @Interruptible
  public static String intAsHexString(int number) {
    char[] buf = new char[10];
    int index = 10;
    while (--index > 1) {
      int digit = number & 0x0000000f;
      buf[index] = digit <= 9 ? (char) ('0' + digit) : (char) ('a' + digit - 10);
      number >>= 4;
    }
    buf[index--] = 'x';
    buf[index] = '0';
    return new String(buf);
  }

  /**
   * Format a 64 bit number as "0x" followed by 16 hex digits.
   * Do this without referencing Long or Character classes,
   * in order to avoid dynamic linking.
   * TODO: move this method to Services.
   * @param number
   * @return a String with the hex representation of the long
   */
  @Interruptible
  public static String longAsHexString(long number) {
    char[] buf = new char[18];
    int index = 18;
    while (--index > 1) {
      int digit = (int) (number & 0x000000000000000fL);
      buf[index] = digit <= 9 ? (char) ('0' + digit) : (char) ('a' + digit - 10);
      number >>= 4;
    }
    buf[index--] = 'x';
    buf[index] = '0';
    return new String(buf);
  }

  /**
   * Format a 32/64 bit number as "0x" followed by 8/16 hex digits.
   * Do this without referencing Integer or Character classes,
   * in order to avoid dynamic linking.
   * TODO: move this method to Services.
   * @param addr  The 32/64 bit number to format.
   * @return a String with the hex representation of an Address
   */
  @Interruptible
  public static String addressAsHexString(Address addr) {
    int len = 2 + (BITS_IN_ADDRESS >> 2);
    char[] buf = new char[len];
    while (--len > 1) {
      int digit = addr.toInt() & 0x0F;
      buf[len] = digit <= 9 ? (char) ('0' + digit) : (char) ('a' + digit - 10);
      addr = addr.toWord().rshl(4).toAddress();
    }
    buf[len--] = 'x';
    buf[len] = '0';
    return new String(buf);
  }

  @SuppressWarnings({"unused", "CanBeFinal", "UnusedDeclaration"})
  // accessed via EntryPoints
  @Entrypoint
  private static int sysWriteLock = 0;
  private static Offset sysWriteLockOffset = Offset.max();

  private static void swLock() {
//    if (sysWriteLockOffset.isMax()) return;
//    while (!Synchronization.testAndSet(Magic.getJTOC(), sysWriteLockOffset, 1)) {
//      ;
//    }
  }

  private static void swUnlock() {
//    if (sysWriteLockOffset.isMax()) return;
//    Synchronization.fetchAndStore(Magic.getJTOC(), sysWriteLockOffset, 0);
  }

  /**
   * Low level print to console.
   * @param value  what is printed
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  private static void write(Atom value) {
    value.sysWrite();
  }

  /**
   * Low level print to console.
   * @param value  what is printed
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void write(RVMMember value) {
    write(value.getMemberRef());
  }

  /**
   * Low level print to console.
   * @param value  what is printed
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void write(MemberReference value) {
    write(value.getType().getName());
    write(".");
    write(value.getName());
    write(" ");
    write(value.getDescriptor());
  }

  /**
   * Low level print to console.
   * @param value   what is printed
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void write(String value) {
    if (value == null) {
      write("null");
    } else {
      if (runningVM) {
        char[] chars = java.lang.JikesRVMSupport.getBackingCharArray(value);
        int numChars = java.lang.JikesRVMSupport.getStringLength(value);
        int offset = java.lang.JikesRVMSupport.getStringOffset(value);
        for (int i = 0; i < numChars; i++) {
          write(chars[offset + i]);
        }
      } else {
        writeNotRunningVM(value);
      }
    }
  }
  @UninterruptibleNoWarn("Interruptible code not reachable at runtime")
  private static void writeNotRunningVM(String value) {
    if (VM.VerifyAssertions) VM._assert(!VM.runningVM);
    System.err.print(value);
  }

  /**
   * Low level print to console.
   * @param value character array that is printed
   * @param len number of characters printed
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void write(char[] value, int len) {
    for (int i = 0, n = len; i < n; ++i) {
      if (runningVM)
        /*  Avoid triggering a potential read barrier
         *
         *  TODO: Convert this to use org.mmtk.vm.Barriers.getArrayNoBarrier
         */ {
        write(Magic.getCharAtOffset(value, Offset.fromIntZeroExtend(i << LOG_BYTES_IN_CHAR)));
      } else {
        write(value[i]);
      }
    }
  }

  /**
   * Low level print of a <code>char</code>to console.
   * @param value       The character to print
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void write(char value) {
    if (runningVM) {
      // sysCall.sysConsoleWriteChar(value);
      PcBootSerialPort.putChar(value);
    } else {
      writeNotRunningVM(value);
    }
  }
  @UninterruptibleNoWarn("Interruptible code not reachable at runtime")
  private static void writeNotRunningVM(char value) {
    if (VM.VerifyAssertions) VM._assert(!VM.runningVM);
    System.err.print(value);
  }

  /**
   * Low level print of <code>double</code> to console.
   *
   * @param value               <code>double</code> to be printed
   * @param postDecimalDigits   Number of decimal places
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void write(double value, int postDecimalDigits) {
    if (runningVM) {
      //sysCall.sysConsoleWriteDouble(value, postDecimalDigits);
    } else {
      writeNotRunningVM(value);
    }
  }
  @UninterruptibleNoWarn("Interruptible code not reachable at runtime")
  private static void writeNotRunningVM(double value) {
    if (VM.VerifyAssertions) VM._assert(!VM.runningVM);
    System.err.print(value);
  }

  /**
   * Low level print of an <code>int</code> to console.
   * @param value       what is printed
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void write(int value) {
    if (runningVM) {
      boolean mode = (value < -(1 << 20) || value > (1 << 20)); // hex only or decimal only
      //sysCall.sysConsoleWriteInteger(value, mode);
      write(value, mode);
    } else {
      writeNotRunningVM(value);
    }
  }
  @UninterruptibleNoWarn("Interruptible code not reachable at runtime")
  private static void writeNotRunningVM(int value) {
    if (VM.VerifyAssertions) VM._assert(!VM.runningVM);
    System.err.print(value);
  }

  static char digitBuffer[] = new char[64];
  static char hexDigits[] = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd','e', 'f' };
  
  /**
   * Low level print to console.
   * @param value       What is printed, as hex only
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeHex(int value) {
    if (runningVM) {
    	long val = value;
    	writeHex(val & 0xffffffffL);
    } else {
      writeHexNotRunningVM(value);
    }
  }
  @UninterruptibleNoWarn("Interruptible code not reachable at runtime")
  private static void writeHexNotRunningVM(int value) {
    if (VM.VerifyAssertions) VM._assert(!VM.runningVM);
    System.err.print(Integer.toHexString(value));
  }

  /**
   * Low level print to console.
   * @param value       what is printed, as hex only
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeHex(long value) {
    if (runningVM) {
        int i;
        long val=value;
        for(i=0; i<64; i++) {
        	digitBuffer[i] = ' ';
        }
        digitBuffer[63] = '0';
        if(val == 0)
        {
            i=62;
        }
        else
        {
            for(i=63; val > 0; i--) {
          	  digitBuffer[i] = hexDigits[(int)(val&0xf)];
          	  val>>=4;
            }
        }
        digitBuffer[i--] = 'x';
        digitBuffer[i] = '0';
        for(; i<64; i++) {
      	  PcBootSerialPort.putChar(digitBuffer[i]);
        }
    } else {
      writeHexNotRunningVM(value);
    }
  }
  @UninterruptibleNoWarn("Interruptible code not reachable at runtime")
  private static void writeHexNotRunningVM(long value) {
    if (VM.VerifyAssertions) VM._assert(!VM.runningVM);
    System.err.print(Long.toHexString(value));
  }

  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeDec(Word value) {
    if (VM.BuildFor32Addr) {
      write(value.toInt());
    } else {
      write(value.toLong());
    }
  }

  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeHex(Word value) {
    if (VM.BuildFor32Addr) {
      writeHex(value.toInt());
    } else {
      writeHex(value.toLong());
    }
  }

  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeHex(Address value) {
    writeHex(value.toWord());
  }

  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeHex(ObjectReference value) {
    writeHex(value.toAddress().toWord());
  }

  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeHex(Extent value) {
    writeHex(value.toWord());
  }

  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeHex(Offset value) {
    writeHex(value.toWord());
  }

  /**
   * Low level print to console.
   * @param value       what is printed, as int only
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeInt(int value) {
    if (runningVM) {
    	long val = (long)value;
    	write(value, false);
    } else {
      writeNotRunningVM(value);
    }
  }
  @UninterruptibleNoWarn("Interruptible code not reachable at runtime")
  private static void writeNotRunningVM(long value) {
    if (VM.VerifyAssertions) VM._assert(!VM.runningVM);
    System.err.print(value);
  }

  /**
   * Low level print to console.
   * @param value   what is printed
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void write(long value) {
    writeHex(value);
  }

  /**
   * Low level print to console.
   * @param value   what is printed
   * @param hexToo  how to print: true  - print as decimal followed by hex
   *                              false - print as decimal only
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void write(long value, boolean hexToo) {
    if (runningVM) {
    	if(hexToo) {
    		writeHex(value);
    	} else {
            int i;
            long val=value;
            for(i=0; i<64; i++) {
            	digitBuffer[i] = ' ';
            }
            digitBuffer[63] = '0';
            for(i=63; val > 0; i--) {
          	  digitBuffer[i] = hexDigits[(int)(val%10)];
          	  val/=10;
            }
            if((value & 0x8000000000000000L) != 0) {
            	digitBuffer[i] = '-';
            }
            for(; i<64; i++) {
            	PcBootSerialPort.putChar(digitBuffer[i]);
            }
    	}
    } else {
      writeNotRunningVM(value);
    }
  }

  /**
   * Low level print to console.
   * @param value   what is printed
   * @param hexToo  how to print: true  - print as decimal followed by hex
   *                              false - print as decimal only
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void write(int value, boolean hexToo) {
    if (runningVM) {
    	if(hexToo) {
    		writeHex(value);
    	} else {
            int i;
            int val=value;
            for(i=0; i<64; i++) {
            	digitBuffer[i] = ' ';
            }
            digitBuffer[63] = '0';
            if(val < 0)
            {
              val = -val;
            }
            for(i=63; val > 0; i--) {
          	  digitBuffer[i] = hexDigits[(int)(val%10)];
          	  val/=10;
            }
            if((value & 0x8000000000000000L) != 0) {
            	digitBuffer[i] = '-';
            }
            for(; i<64; i++) {
            	PcBootSerialPort.putChar(digitBuffer[i]);
            }
    	}
    } else {
      writeNotRunningVM(value);
    }
  }

  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeField(int fieldWidth, String s) {
    write(s);
    int len = getStringLength(s);
    while (fieldWidth > len++) write(" ");
  }

  @UninterruptibleNoWarn("Interruptible code not reachable at runtime")
  private static int getStringLength(String s) {
    if (VM.runningVM) {
      return java.lang.JikesRVMSupport.getStringLength(s);
    } else {
      return s.length();
    }
  }
  /**
   * Low level print to console.
   * @param value       print value and left-fill with enough spaces to print at least fieldWidth characters
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeField(int fieldWidth, int value) {
    int len = 1, temp = value;
    if (temp < 0) {
      len++;
      temp = -temp;
    }
    while (temp >= 10) {
      len++;
      temp /= 10;
    }
    while (fieldWidth > len++) write(" ");
    if (runningVM) {
      // sysCall.sysConsoleWriteInteger(value, 0);
    } else {
      writeNotRunningVM(value);
    }
  }

  /**
   * Low level print of the {@link Atom} <code>s</code> to the console.
   * Left-fill with enough spaces to print at least <code>fieldWidth</code>
   * characters
   * @param fieldWidth  Minimum width to print.
   * @param s       The {@link Atom} to print.
   */
  @NoInline
  /* don't waste code space inlining these --dave */
  public static void writeField(int fieldWidth, Atom s) {
    int len = s.length();
    while (fieldWidth > len++) write(" ");
    write(s);
  }

  public static void writeln() {
    write('\n');
  }

  public static void write(double d) {
    write(d, 2);
  }

  public static void write(Word addr) {
    writeHex(addr);
  }

  public static void write(Address addr) {
    writeHex(addr);
  }

  public static void write(ObjectReference object) {
    writeHex(object);
  }

  public static void write(Offset addr) {
    writeHex(addr);
  }

  public static void write(Extent addr) {
    writeHex(addr);
  }

  public static void write(boolean b) {
    write(b ? "true" : "false");
  }

  /**
   * A group of multi-argument sysWrites with optional newline.  Externally visible methods.
   */
  @NoInline
  public static void sysWrite(Atom a) {
    swLock();
    write(a);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(Atom a) {
    swLock();
    write(a);
    write("\n");
    swUnlock();
  }

  @NoInline
  public static void sysWrite(RVMMember m) {
    swLock();
    write(m);
    swUnlock();
  }

  @NoInline
  public static void sysWrite(MemberReference mr) {
    swLock();
    write(mr);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln() {
    swLock();
    write("\n");
    swUnlock();
  }

  @NoInline
  public static void sysWrite(char c) { write(c); }

  @NoInline
  public static void sysWriteField(int w, int v) {
    swLock();
    writeField(w, v);
    swUnlock();
  }

  @NoInline
  public static void sysWriteField(int w, String s) {
    swLock();
    writeField(w, s);
    swUnlock();
  }

  @NoInline
  public static void sysWriteHex(int v) {
    swLock();
    writeHex(v);
    swUnlock();
  }

  @NoInline
  public static void sysWriteHex(long v) {
    swLock();
    writeHex(v);
    swUnlock();
  }

  @NoInline
  public static void sysWriteHex(Address v) {
    swLock();
    writeHex(v);
    swUnlock();
  }

  @NoInline
  public static void sysWriteInt(int v) {
    swLock();
    writeInt(v);
    swUnlock();
  }

  @NoInline
  public static void sysWriteLong(long v) {
    swLock();
    write(v, false);
    swUnlock();
  }

  @NoInline
  public static void sysWrite(double d, int p) {
    swLock();
    write(d, p);
    swUnlock();
  }

  @NoInline
  public static void sysWrite(double d) {
    swLock();
    write(d);
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s) {
    swLock();
    write(s);
    swUnlock();
  }

  @NoInline
  public static void sysWrite(char[] c, int l) {
    swLock();
    write(c, l);
    swUnlock();
  }

  @NoInline
  public static void sysWrite(Address a) {
    swLock();
    write(a);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(Address a) {
    swLock();
    write(a);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(ObjectReference o) {
    swLock();
    write(o);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(ObjectReference o) {
    swLock();
    write(o);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(Offset o) {
    swLock();
    write(o);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(Offset o) {
    swLock();
    write(o);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(Word w) {
    swLock();
    write(w);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(Word w) {
    swLock();
    write(w);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(Extent e) {
    swLock();
    write(e);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(Extent e) {
    swLock();
    write(e);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(boolean b) {
    swLock();
    write(b);
    swUnlock();
  }

  @NoInline
  public static void sysWrite(int i) {
    swLock();
    write(i);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(int i) {
    swLock();
    write(i);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(double d) {
    swLock();
    write(d);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(long l) {
    swLock();
    write(l);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(boolean b) {
    swLock();
    write(b);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s) {
    swLock();
    write(s);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s, Atom a) {
    swLock();
    write(s);
    write(a);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s, int i) {
    swLock();
    write(s);
    write(i);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s, int i) {
    swLock();
    write(s);
    write(i);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s, boolean b) {
    swLock();
    write(s);
    write(b);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s, boolean b) {
    swLock();
    write(s);
    write(b);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s, double d) {
    swLock();
    write(s);
    write(d);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s, double d) {
    swLock();
    write(s);
    write(d);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(double d, String s) {
    swLock();
    write(d);
    write(s);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(double d, String s) {
    swLock();
    write(d);
    write(s);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s, long i) {
    swLock();
    write(s);
    write(i);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s, long i) {
    swLock();
    write(s);
    write(i);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, long i1,String s2, long i2) {
    swLock();
    write(s1);
    write(i1);
    write(s2);
    write(i2);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(int i, String s) {
    swLock();
    write(i);
    write(s);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(int i, String s) {
    swLock();
    write(i);
    write(s);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, String s2) {
    swLock();
    write(s1);
    write(s2);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2) {
    swLock();
    write(s1);
    write(s2);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s, Address a) {
    swLock();
    write(s);
    write(a);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s, Address a) {
    swLock();
    write(s);
    write(a);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s, ObjectReference r) {
    swLock();
    write(s);
    write(r);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s, ObjectReference r) {
    swLock();
    write(s);
    write(r);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s, Offset o) {
    swLock();
    write(s);
    write(o);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s, Offset o) {
    swLock();
    write(s);
    write(o);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s, Word w) {
    swLock();
    write(s);
    write(w);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s, Word w) {
    swLock();
    write(s);
    write(w);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, String s2, Address a) {
    swLock();
    write(s1);
    write(s2);
    write(a);
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, Address a, String s2) {
    swLock();
    write(s1);
    write(a);
    write(s2);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2, Address a) {
    swLock();
    write(s1);
    write(s2);
    write(a);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, Address a,String s2) {
    swLock();
    write(s1);
    write(a);
    write(s2);
    writeln();
    swUnlock();
  }
  @NoInline
  public static void sysWriteln(String s1, Address a1,Address a2) {
    swLock();
    write(s1);
    write(a1);
    write(" ");
    write(a2);
    writeln();
    swUnlock();
  }
  @NoInline
  public static void sysWrite(String s1, String s2, int i) {
    swLock();
    write(s1);
    write(s2);
    write(i);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(int i, Address a, RVMMethod m) {
    swLock();
    write(i);
    write(" ");
    write(a);
    write(" ");
    write(m.getDeclaringClass().getDescriptor());
    write(".");
    write(m.getName());
    write(m.getDescriptor());
    write("\n");
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(int i, Address a, Address b) {
    swLock();
    write(i);
    write(" ");
    write(a);
    write(" ");
    write(b);
    write("\n");
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2, int i) {
    swLock();
    write(s1);
    write(s2);
    write(i);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, int i, String s2) {
    swLock();
    write(s1);
    write(i);
    write(s2);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, int i, String s2) {
    swLock();
    write(s1);
    write(i);
    write(s2);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, Offset o, String s2) {
    swLock();
    write(s1);
    write(o);
    write(s2);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, Offset o, String s2) {
    swLock();
    write(s1);
    write(o);
    write(s2);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, String s2, String s3) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2, String s3) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2, String s3, Address a) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    write(a);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(int i1, String s, int i2) {
    swLock();
    write(i1);
    write(s);
    write(i2);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(int i1, String s, int i2) {
    swLock();
    write(i1);
    write(s);
    write(i2);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(int i1, String s1, String s2) {
    swLock();
    write(i1);
    write(s1);
    write(s2);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(int i1, String s1, String s2) {
    swLock();
    write(i1);
    write(s1);
    write(s2);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, String s2, String s3, String s4) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    write(s4);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2, String s3, String s4) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    write(s4);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, String s2, String s3, String s4, String s5) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    write(s4);
    write(s5);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2, String s3, String s4, String s5) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    write(s4);
    write(s5);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, int i, String s3, Address a, String s5) {
    swLock();
    write(s1);
    write(i);
    write(s3);
    write(a);
    write(s5);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(int i, String s, Address a) {
    swLock();
    write(i);
    write(s);
    write(a);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, int i1, String s2, int i2) {
    swLock();
    write(s1);
    write(i1);
    write(s2);
    write(i2);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, int i1, String s2, int i2) {
    swLock();
    write(s1);
    write(i1);
    write(s2);
    write(i2);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, int i, String s2, Address a) {
    swLock();
    write(s1);
    write(i);
    write(s2);
    write(a);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, int i, String s2, Word w) {
    swLock();
    write(s1);
    write(i);
    write(s2);
    write(w);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, int i, String s2, double d) {
    swLock();
    write(s1);
    write(i);
    write(s2);
    write(d);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, int i, String s2, Word w, String s3) {
    swLock();
    write(s1);
    write(i);
    write(s2);
    write(w);
    write(s3);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, int i1, String s2, int i2, String s3) {
    swLock();
    write(s1);
    write(i1);
    write(s2);
    write(i2);
    write(s3);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, int i1, String s2, int i2, String s3, int i3) {
    swLock();
    write(s1);
    write(i1);
    write(s2);
    write(i2);
    write(s3);
    write(i3);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, int i1, String s2, long l1) {
    swLock();
    write(s1);
    write(i1);
    write(s2);
    write(l1);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, int i1, String s2, long l1) {
    swLock();
    write(s1);
    write(i1);
    write(s2);
    write(l1);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, Offset o, String s2, int i) {
    swLock();
    write(s1);
    write(o);
    write(s2);
    write(i);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, Offset o, String s2, int i) {
    swLock();
    write(s1);
    write(o);
    write(s2);
    write(i);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, double d, String s2) {
    swLock();
    write(s1);
    write(d);
    write(s2);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, double d, String s2) {
    swLock();
    write(s1);
    write(d);
    write(s2);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, long l1, String s2, long l2, String s3) {
    swLock();
    write(s1);
    write(l1);
    write(s2);
    write(l2);
    write(s3);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, String s2, int i1, String s3) {
    swLock();
    write(s1);
    write(s2);
    write(i1);
    write(s3);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2, int i1, String s3) {
    swLock();
    write(s1);
    write(s2);
    write(i1);
    write(s3);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, String s2, String s3, int i1) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    write(i1);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2, String s3, int i1) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    write(i1);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, String s2, String s3, String s4, int i5, String s6) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    write(s4);
    write(i5);
    write(s6);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2, String s3, String s4, int i5, String s6) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    write(s4);
    write(i5);
    write(s6);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(int i, String s1, double d, String s2) {
    swLock();
    write(i);
    write(s1);
    write(d);
    write(s2);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(int i, String s1, double d, String s2) {
    swLock();
    write(i);
    write(s1);
    write(d);
    write(s2);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, String s2, String s3, int i1, String s4) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    write(i1);
    write(s4);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, String s2, String s3, int i1, String s4) {
    swLock();
    write(s1);
    write(s2);
    write(s3);
    write(i1);
    write(s4);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, Address a1, String s2, Address a2) {
    swLock();
    write(s1);
    write(a1);
    write(s2);
    write(a2);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, Address a1, String s2, Address a2) {
    swLock();
    write(s1);
    write(a1);
    write(s2);
    write(a2);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWrite(String s1, Address a, String s2, int i) {
    swLock();
    write(s1);
    write(a);
    write(s2);
    write(i);
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s1, Address a, String s2, int i) {
    swLock();
    write(s1);
    write(a);
    write(s2);
    write(i);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void sysWriteln(String s0, Address a1, String s1, Word w1, String s2, int i1, String s3, int i2, String s4, Word w2, String s5, int i3) {
    swLock();
    write(s0);
    write(a1);
    write(s1);
    write(w1);
    write(s2);
    write(i1);
    write(s3);
    write(i2);
    write(s4);
    write(w2);
    write(s5);
    write(i3);
    writeln();
    swUnlock();
  }

  private static void showThread() {
    write("Thread ");
    write(RVMThread.getCurrentThread().getThreadSlot());
    write(": ");
  }

  @NoInline
  public static void tsysWriteln(String s) {
    swLock();
    showThread();
    write(s);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void tsysWriteln(String s1, String s2, String s3, int i4, String s5, String s6) {
    swLock();
    showThread();
    write(s1);
    write(s2);
    write(s3);
    write(i4);
    write(s5);
    write(s6);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void tsysWriteln(String s1, String s2, String s3, String s4, String s5, String s6, String s7, int i8,
                                  String s9, String s10, String s11, String s12, String s13) {
    swLock();
    showThread();
    write(s1);
    write(s2);
    write(s3);
    write(s4);
    write(s5);
    write(s6);
    write(s7);
    write(i8);
    write(s9);
    write(s10);
    write(s11);
    write(s12);
    write(s13);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void tsysWriteln(String s1, String s2, String s3, String s4, String s5, String s6, String s7, int i8,
                                  String s9, String s10, String s11, String s12, String s13, int i14) {
    swLock();
    showThread();
    write(s1);
    write(s2);
    write(s3);
    write(s4);
    write(s5);
    write(s6);
    write(s7);
    write(i8);
    write(s9);
    write(s10);
    write(s11);
    write(s12);
    write(s13);
    write(i14);
    writeln();
    swUnlock();
  }
  @NoInline
  public static void tsysWrite(char[] c, int l) {
    swLock();
    showThread();
    write(c, l);
    swUnlock();
  }

  @NoInline
  public static void tsysWriteln(Address a) {
    swLock();
    showThread();
    write(a);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void tsysWriteln(String s, int i) {
    swLock();
    showThread();
    write(s);
    write(i);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void tsysWriteln(String s, Address a) {
    swLock();
    showThread();
    write(s);
    write(a);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void tsysWriteln(String s1, Address a1, String s2, Address a2) {
    swLock();
    showThread();
    write(s1);
    write(a1);
    write(s2);
    write(a2);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void tsysWriteln(String s1, Address a1, String s2, Address a2, String s3, Address a3) {
    swLock();
    showThread();
    write(s1);
    write(a1);
    write(s2);
    write(a2);
    write(s3);
    write(a3);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void tsysWriteln(String s1, Address a1, String s2, Address a2, String s3, Address a3, String s4,
                                 Address a4) {
    swLock();
    showThread();
    write(s1);
    write(a1);
    write(s2);
    write(a2);
    write(s3);
    write(a3);
    write(s4);
    write(a4);
    writeln();
    swUnlock();
  }

  @NoInline
  public static void tsysWriteln(String s1, Address a1, String s2, Address a2, String s3, Address a3, String s4,
                                 Address a4, String s5, Address a5) {
    swLock();
    showThread();
    write(s1);
    write(a1);
    write(s2);
    write(a2);
    write(s3);
    write(a3);
    write(s4);
    write(a4);
    write(s5);
    write(a5);
    writeln();
    swUnlock();
  }

  /**
   * Produce a message requesting a bug report be submitted
   */
  @NoInline
  public static void bugReportMessage() {
    VM.sysWriteln("********************************************************************************");
    VM.sysWriteln("*                      Abnormal termination of Jikes RVM                       *\n"+
                  "* Jikes RVM terminated abnormally indicating a problem in the virtual machine. *\n"+
                  "* Jikes RVM relies on community support to get debug information. Help improve *\n"+
                  "* Jikes RVM for everybody by reporting this error. Please see:                 *\n"+
                  "*                      http://jikesrvm.org/Reporting+Bugs                      *");
    VM.sysWriteln("********************************************************************************");
  }

  /**
   * Exit virtual machine due to internal failure of some sort.
   * @param message  error message describing the problem
   */
  @NoInline
  public static void sysFail(String message) {
    handlePossibleRecursiveCallToSysFail(message);

    // print a traceback and die
    if(!RVMThread.getCurrentThread().isCollectorThread()) {
      RVMThread.traceback(message);
    } else {
      VM.sysWriteln("Died in GC:");
      RVMThread.traceback(message);
      VM.sysWriteln("Virtual machine state:");
      RVMThread.dumpVirtualMachine();
    }
    bugReportMessage();
    if (VM.runningVM) {
      VM.shutdown(EXIT_STATUS_SYSFAIL);
    } else {
      VM.sysExit(EXIT_STATUS_SYSFAIL);
    }
    if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
  }

  @NoInline
  public static void sysFailTrap(String message)
  {
      VM.sysWriteln(Magic.getFramePointer());
      RVMThread.trapTraceback(message);
      VM.shutdown(EXIT_STATUS_SYSFAIL);
  }
  /**
   * Exit virtual machine due to internal failure of some sort.  This
   * two-argument form is  needed for us to call before the VM's Integer class
   * is initialized.
   *
   * @param message  error message describing the problem
   * @param number  an integer to append to <code>message</code>.
   */
  @NoInline
  public static void sysFail(String message, int number) {
    handlePossibleRecursiveCallToSysFail(message, number);

    // print a traceback and die
    RVMThread.traceback(message, number);
    bugReportMessage();
    if (VM.runningVM) {
      VM.shutdown(EXIT_STATUS_SYSFAIL);
    } else {
      VM.sysExit(EXIT_STATUS_SYSFAIL);
    }
    if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
  }

  /**
   * Exit virtual machine.
   * @param value  value to pass to host o/s
   */
  @NoInline
  @UninterruptibleNoWarn("We're never returning to the caller, so even though this code is preemptible it is safe to call from any context")
  public static void sysExit(int value) {
    handlePossibleRecursiveCallToSysExit();

    if (VM.countThreadTransitions) {
      RVMThread.reportThreadTransitionCounts();
    }

    if (Options.stackTraceAtExit) {
      VM.sysWriteln("[Here is the context of the call to VM.sysExit(", value, ")...:");
      VM.disableGC();
      RVMThread.dumpStack();
      VM.enableGC();
      VM.sysWriteln("... END context of the call to VM.sysExit]");
    }
    if (runningVM) {
      Callbacks.notifyExit(value);
      VM.shutdown(value);
    } else {
      System.exit(value);
    }
    if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
  }

  /**
   * Shut down the virtual machine.
   * Should only be called if the VM is running.
   * @param value  exit value
   */
  @Uninterruptible
  public static void shutdown(int value) {
    handlePossibleRecursiveShutdown();

    if (VM.VerifyAssertions) VM._assert(VM.runningVM);
    VM.sysWriteln("VM is shutting down!");
    while(true) Magic.halt();
//    if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
  }

  private static int inSysFail = 0;

  public static boolean sysFailInProgress() {
    return inSysFail > 0;
  }

  private static void handlePossibleRecursiveCallToSysFail(String message) {
    handlePossibleRecursiveExit("sysFail", ++inSysFail, message);
  }

  private static void handlePossibleRecursiveCallToSysFail(String message, int number) {
    handlePossibleRecursiveExit("sysFail", ++inSysFail, message, number);
  }

  private static int inSysExit = 0;

  private static void handlePossibleRecursiveCallToSysExit() {
    handlePossibleRecursiveExit("sysExit", ++inSysExit);
  }

  private static int inShutdown = 0;

  /** Used only by VM.shutdown() */
  private static void handlePossibleRecursiveShutdown() {
    handlePossibleRecursiveExit("shutdown", ++inShutdown);
  }

  private static void handlePossibleRecursiveExit(String called, int depth) {
    handlePossibleRecursiveExit(called, depth, null);
  }

  private static void handlePossibleRecursiveExit(String called, int depth, String message) {
    handlePossibleRecursiveExit(called, depth, message, false, -9999999);
  }

  private static void handlePossibleRecursiveExit(String called, int depth, String message, int number) {
    handlePossibleRecursiveExit(called, depth, message, true, number);
  }

  /** @param called Name of the function called: "sysExit", "sysFail", or
   *    "shutdown".
   * @param depth How deep are we in that function?
   * @param message What message did it have?  null means this particular
   *    shutdown function  does not come with a message.
   * @param showNumber Print <code>number</code> following
   *    <code>message</code>?
   * @param number Print this number, if <code>showNumber</code> is {@code true}. */
  private static void handlePossibleRecursiveExit(String called, int depth, String message, boolean showNumber,
                                                  int number) {
    if (depth > 1 &&
        (depth <=
         maxSystemTroubleRecursionDepth + VM.maxSystemTroubleRecursionDepthBeforeWeStopVMSysWrite)) {
      if (showNumber) {
        tsysWriteln("VM.",
                     called,
                     "(): We're in a",
                     " (likely)",
                     " recursive call to VM.",
                     called,
                     "(), ",
                     depth,
                     " deep\n",
                     message == null ? "" : "   ",
                     message == null ? "" : called,
                     message == null ? "" : " was called with the message: ",
                     message == null ? "" : message,
                     number);
      } else {
        tsysWriteln("VM.",
                     called,
                     "(): We're in a",
                     " (likely)",
                     " recursive call to VM.",
                     called,
                     "(), ",
                     depth,
                     " deep\n",
                     message == null ? "" : "   ",
                     message == null ? "" : called,
                     message == null ? "" : " was called with the message: ",
                     message == null ? "" : message);
      }
    }
    if (depth > maxSystemTroubleRecursionDepth) {
      dieAbruptlyRecursiveSystemTrouble();
      if (VM.VerifyAssertions) VM._assert(VM.NOT_REACHED);
    }
  }

  /** Have we already called dieAbruptlyRecursiveSystemTrouble()?
   Only for use if we're recursively shutting down!  Used by
   dieAbruptlyRecursiveSystemTrouble() only.  */

  private static boolean inDieAbruptlyRecursiveSystemTrouble = false;

  public static void dieAbruptlyRecursiveSystemTrouble() {
    if (!inDieAbruptlyRecursiveSystemTrouble) {
      inDieAbruptlyRecursiveSystemTrouble = true;
      sysWriteln("VM.dieAbruptlyRecursiveSystemTrouble(): Dying abruptly",
                 "; we're stuck in a recursive shutdown/exit.");
    }
    /* Emergency death. */
    Magic.halt();
    /* And if THAT fails, go into an infinite loop.  Ugly, but it's better than
       returning from this function and leading to yet more cascading errors.
       and misleading error messages.   (To the best of my knowledge, we have
       never yet reached this point.)  */
    while (true) {
      ;
    }
  }

  //----------------//
  // implementation //
  //----------------//

  /**
   * Create class instances needed for boot image or initialize classes
   * needed by tools.
   * @param bootstrapClasspath places where VM implementation class reside
   * @param bootCompilerArgs command line arguments to pass along to the
   *                         boot compiler's init routine.
   */
  @Interruptible
  private static void init(String bootstrapClasspath, String[] bootCompilerArgs) {
    if (VM.VerifyAssertions) VM._assert(!VM.runningVM);

    // create dummy boot record
    //
    BootRecord.the_boot_record = new BootRecord();

    // initialize type subsystem and classloader
    RVMClassLoader.init(bootstrapClasspath);

    // initialize remaining subsystems needed for compilation
    //
    if (writingBootImage) {
      // initialize compiler that builds boot image
      BootImageCompiler.init(bootCompilerArgs);
    }
    RuntimeEntrypoints.init();
    RVMThread.init();
  }

  /**
   * The disableGC() and enableGC() methods are for use as guards to protect
   * code that must deal with raw object addresses in a collection-safe manner
   * (i.e. code that holds raw pointers across "gc-sites").<p>
   *
   * Authors of code running while GC is disabled must be certain not to
   * allocate objects explicitly via "new", or implicitly via methods that,
   * in turn, call "new" (such as string concatenation expressions that are
   * translated by the java compiler into String() and StringBuffer()
   * operations). Furthermore, to prevent deadlocks, code running with GC
   * disabled must not lock any objects. This means the code must not execute
   * any bytecodes that require runtime support (e.g. via RuntimeEntrypoints)
   * such as:
   * <ul>
   *   <li>calling methods or accessing fields of classes that haven't yet
   *     been loaded/resolved/instantiated
   *   <li>calling synchronized methods
   *   <li>entering synchronized blocks
   *   <li>allocating objects with "new"
   *   <li>throwing exceptions
   *   <li>executing trap instructions (including stack-growing traps)
   *   <li>storing into object arrays, except when runtime types of lhs & rhs
   *     match exactly
   *   <li>typecasting objects, except when runtime types of lhs & rhs
   *     match exactly
   * </ul>
   *
   * <p>
   * Recommendation: as a debugging aid, Allocator implementations
   * should test "Thread.disallowAllocationsByThisThread" to verify that
   * they are never called while GC is disabled.
   */
  @Inline
  @Unpreemptible("We may boost the size of the stack with GC disabled and may get preempted doing this")
  public static void disableGC() {
    // disableGC(false);           // Recursion is not allowed in this context.
  }

  /**
   * disableGC: Disable GC if it hasn't already been disabled.  This
   * enforces a stack discipline; we need it for the JNI Get*Critical and
   * Release*Critical functions.  Should be matched with a subsequent call to
   * enableGC().
   */
  @Inline
  @Unpreemptible("We may boost the size of the stack with GC disabled and may get preempted doing this")
  public static void disableGC(boolean recursiveOK) {
    // current (non-GC) thread is going to be holding raw addresses, therefore we must:
    //
    // 1. make sure we have enough stack space to run until GC is re-enabled
    //    (otherwise we might trigger a stack reallocation)
    //    (We can't resize the stack if there's a native frame, so don't
    //     do it and hope for the best)
    //
    // 2. force all other threads that need GC to wait until this thread
    //    is done with the raw addresses
    //
    // 3. ensure that this thread doesn't try to allocate any objects
    //    (because an allocation attempt might trigger a collection that
    //    would invalidate the addresses we're holding)
    //

    RVMThread myThread = RVMThread.getCurrentThread();

    // 0. Sanity Check; recursion
    int gcDepth = myThread.getDisableGCDepth();
    if (VM.VerifyAssertions) VM._assert(gcDepth >= 0);
    gcDepth++;
    myThread.setDisableGCDepth(gcDepth);
    if (gcDepth > 1) {
      return;                   // We've already disabled it.
    }

    // 1.
    //
    if (Magic.getFramePointer().minus(ArchitectureSpecific.StackframeLayoutConstants.STACK_SIZE_GCDISABLED)
        .LT(myThread.stackLimit)) {
      RVMThread.resizeCurrentStack(myThread.getStackLength()+
          ArchitectureSpecific.StackframeLayoutConstants.STACK_SIZE_GCDISABLED, null);
    }

    // 2.
    //
    myThread.disableYieldpoints();

    // 3.
    //
    if (VM.VerifyAssertions) {
      if (!recursiveOK) {
        VM._assert(!myThread.getDisallowAllocationsByThisThread()); // recursion not allowed
      }
      myThread.setDisallowAllocationsByThisThread();
    }
  }

  /**
   * enable GC; entry point when recursion is not OK.
   */
  @Inline
  public static void enableGC() {
    // enableGC(false);            // recursion not OK.
  }

  /**
   * enableGC(): Re-Enable GC if we're popping off the last
   * possibly-recursive {@link #disableGC} request.  This enforces a stack discipline;
   * we need it for the JNI Get*Critical and Release*Critical functions.
   * Should be matched with a preceding call to {@link #disableGC}.
   */
  @Inline
  public static void enableGC(boolean recursiveOK) {
    RVMThread myThread = RVMThread.getCurrentThread();
    int gcDepth = myThread.getDisableGCDepth();
    if (VM.VerifyAssertions) {
      VM._assert(gcDepth >= 1);
      VM._assert(myThread.getDisallowAllocationsByThisThread());
    }
    gcDepth--;
    myThread.setDisableGCDepth(gcDepth);
    if (gcDepth > 0) {
      return;
    }

    // Now the actual work of re-enabling GC.
    myThread.clearDisallowAllocationsByThisThread();
    myThread.enableYieldpoints();
  }

  /**
   * Is this a build for 32bit addressing? NB. this method is provided
   * to give a hook to the IA32 assembler that won't be compiled away
   * by javac
   */
  public static boolean buildFor32Addr() {
    return BuildFor32Addr;
  }

  /**
   * Is this a build for SSE2? NB. this method is provided to give a
   * hook to the IA32 assembler that won't be compiled away by javac
   */
  public static boolean buildForSSE2() {
    return BuildForSSE2;
  }
  
  public static void hexDump(byte data[])
  {
    hexDump(data, 0, data.length);
  }
  
  /**
   * Print data from array at offset
   * @param data
   * @param offset
   */
  public static void hexDump(byte data[], int offset, int size)
  {
    int i;
    
    for(i=offset; i < size; i++)
    {
      if(i!=0 && (i%16) == 0)
      {
        VM.sysWriteln();
        if(data[i] < 0x10 && data[i] >=0)
        {
          VM.sysWrite('0');
        }
        VM.sysWrite(Integer.toHexString(data[i]&0xff), " ");
      }
      else
      {
        if(data[i] < 0x10 && data[i] >=0)
        {
          VM.sysWrite('0');
        }
        VM.sysWrite(Integer.toHexString(data[i]&0xff), " ");
      }
    }
    VM.sysWriteln();
  }
}


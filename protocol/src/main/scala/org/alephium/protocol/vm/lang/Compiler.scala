// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.protocol.vm.lang

import scala.collection.{immutable, mutable}

import fastparse.Parsed

import org.alephium.protocol.vm._
import org.alephium.protocol.vm.lang.Ast.MultiContract
import org.alephium.util.AVector

// scalastyle:off number.of.methods
// scalastyle:off file.size.limit
object Compiler {
  type CompiledContract = (StatefulContract, Ast.Contract, AVector[String])
  type CompiledScript   = (StatefulScript, Ast.TxScript, AVector[String])

  def compileAssetScript(input: String): Either[Error, (StatelessScript, AVector[String])] =
    try {
      fastparse.parse(input, StatelessParser.assetScript(_)) match {
        case Parsed.Success(script, _) =>
          val state = State.buildFor(script)
          Right((script.genCode(state), state.getWarnings))
        case failure: Parsed.Failure =>
          Left(Error.parse(failure))
      }
    } catch {
      case e: Error => Left(e)
    }

  def compileTxScript(input: String): Either[Error, StatefulScript] =
    compileTxScript(input, 0)

  def compileTxScript(input: String, index: Int): Either[Error, StatefulScript] =
    compileTxScriptFull(input, index).map(_._1)

  def compileTxScriptFull(input: String): Either[Error, CompiledScript] =
    compileTxScriptFull(input, 0)

  def compileTxScriptFull(input: String, index: Int): Either[Error, CompiledScript] =
    compileStateful(input, _.genStatefulScript(index))

  def compileContract(input: String): Either[Error, StatefulContract] =
    compileContract(input, 0)

  def compileContract(input: String, index: Int): Either[Error, StatefulContract] =
    compileContractFull(input, index).map(_._1)

  def compileContractFull(input: String): Either[Error, CompiledContract] =
    compileContractFull(input, 0)

  def compileContractFull(input: String, index: Int): Either[Error, CompiledContract] =
    compileStateful(input, _.genStatefulContract(index))

  private def compileStateful[T](input: String, genCode: MultiContract => T): Either[Error, T] = {
    try {
      compileMultiContract(input).map(genCode)
    } catch {
      case e: Error => Left(e)
    }
  }

  def compileProject(
      input: String
  ): Either[Error, (AVector[CompiledContract], AVector[CompiledScript])] = {
    try {
      compileMultiContract(input).map { multiContract =>
        val statefulContracts = multiContract.genStatefulContracts().map(c => (c._1, c._2, c._3))
        val statefulScripts   = multiContract.genStatefulScripts()
        (statefulContracts, statefulScripts)
      }
    } catch {
      case e: Error => Left(e)
    }
  }

  def compileMultiContract(input: String): Either[Error, MultiContract] = {
    try {
      fastparse.parse(input, StatefulParser.multiContract(_)) match {
        case Parsed.Success(multiContract, _) => Right(multiContract.extendedContracts())
        case failure: Parsed.Failure          => Left(Error.parse(failure))
      }
    } catch {
      case e: Error => Left(e)
    }
  }

  def compileState(stateRaw: String): Either[Error, AVector[Val]] = {
    try {
      fastparse.parse(stateRaw, StatefulParser.state(_)) match {
        case Parsed.Success(state, _) => Right(AVector.from(state.map(_.v)))
        case failure: Parsed.Failure  => Left(Error.parse(failure))
      }
    } catch {
      case e: Error => Left(e)
    }
  }

  trait FuncInfo[-Ctx <: StatelessContext] {
    def name: String
    def isPublic: Boolean
    def isVariadic: Boolean = false
    def usePreapprovedAssets: Boolean
    def useAssetsInContract: Boolean
    def isReadonly: Boolean
    def getReturnType(inputType: Seq[Type]): Seq[Type]
    def getReturnLength(inputType: Seq[Type]): Int = {
      val retTypes = getReturnType(inputType)
      Type.flattenTypeLength(retTypes)
    }
    def genCode(inputType: Seq[Type]): Seq[Instr[Ctx]]
    def genExternalCallCode(typeId: Ast.TypeId): Seq[Instr[StatefulContext]]
  }

  final case class Error(message: String) extends Exception(message)
  object Error {
    def parse(failure: Parsed.Failure): Error = Error(s"Parser failed: ${failure.trace().longMsg}")
  }

  def expectOneType(ident: Ast.Ident, tpe: Seq[Type]): Type = {
    if (tpe.length == 1) {
      tpe(0)
    } else {
      throw Error(s"Try to set types $tpe for variable $ident")
    }
  }

  type VarInfoBuilder = (Type, Boolean, Boolean, Byte, Boolean) => VarInfo
  sealed trait VarInfo {
    def tpe: Type
    def isMutable: Boolean
    def isUnused: Boolean
    def isGenerated: Boolean
    def isLocal: Boolean
  }
  object VarInfo {
    final case class Local(
        tpe: Type,
        isMutable: Boolean,
        isUnused: Boolean,
        index: Byte,
        isGenerated: Boolean
    ) extends VarInfo {
      def isLocal: Boolean = true
    }
    final case class Field(
        tpe: Type,
        isMutable: Boolean,
        isUnused: Boolean,
        index: Byte,
        isGenerated: Boolean
    ) extends VarInfo {
      def isLocal: Boolean = false
    }
    final case class Template(tpe: Type, index: Int) extends VarInfo {
      def isMutable: Boolean   = false
      def isUnused: Boolean    = false
      def isGenerated: Boolean = false
      def isLocal: Boolean     = false
    }
    final case class ArrayRef[Ctx <: StatelessContext](
        isMutable: Boolean,
        isUnused: Boolean,
        isGenerated: Boolean,
        ref: ArrayTransformer.ArrayRef[Ctx]
    ) extends VarInfo {
      def tpe: Type        = ref.tpe
      def isLocal: Boolean = ref.isLocal
    }
    final case class Constant[Ctx <: StatelessContext](
        tpe: Type,
        instrs: Seq[Instr[Ctx]]
    ) extends VarInfo {
      def isMutable: Boolean   = false
      def isUnused: Boolean    = false
      def isGenerated: Boolean = false
      def isLocal: Boolean     = true
    }
  }
  trait ContractFunc[Ctx <: StatelessContext] extends FuncInfo[Ctx] {
    def argsType: Seq[Type]
    def returnType: Seq[Type]
  }
  final case class SimpleFunc[Ctx <: StatelessContext](
      id: Ast.FuncId,
      isPublic: Boolean,
      usePreapprovedAssets: Boolean,
      useAssetsInContract: Boolean,
      isReadonly: Boolean,
      argsType: Seq[Type],
      returnType: Seq[Type],
      index: Byte
  ) extends ContractFunc[Ctx] {
    def name: String = id.name

    override def getReturnType(inputType: Seq[Type]): Seq[Type] = {
      if (inputType == argsType) {
        returnType
      } else {
        throw Error(s"Invalid args type $inputType for function $name")
      }
    }

    override def genCode(inputType: Seq[Type]): Seq[Instr[StatelessContext]] = {
      Seq(CallLocal(index))
    }

    override def genExternalCallCode(typeId: Ast.TypeId): Seq[Instr[StatefulContext]] = {
      if (isPublic) {
        Seq(CallExternal(index))
      } else {
        throw Error(s"Call external private function of ${typeId.name}")
      }
    }
  }
  object SimpleFunc {
    def from[Ctx <: StatelessContext](funcs: Seq[Ast.FuncDef[Ctx]]): Seq[SimpleFunc[Ctx]] = {
      funcs.view.zipWithIndex.map { case (func, index) =>
        new SimpleFunc[Ctx](
          func.id,
          func.isPublic,
          func.usePreapprovedAssets,
          func.useAssetsInContract,
          func.useReadonly,
          func.args.map(_.tpe),
          func.rtypes,
          index.toByte
        )
      }.toSeq
    }
  }

  final case class EventInfo(typeId: Ast.TypeId, fieldTypes: Seq[Type]) {
    def checkFieldTypes(argTypes: Seq[Type]): Unit = {
      if (fieldTypes != argTypes) {
        val eventAbi = s"""${typeId.name}${fieldTypes.mkString("(", ", ", ")")}"""
        throw Error(s"Invalid args type $argTypes for event $eventAbi")
      }
    }
  }

  object State {
    private val maxVarIndex: Int = 0xff

    // scalastyle:off cyclomatic.complexity
    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    // we have checked the index type(U256)
    def getConstantIndex[Ctx <: StatelessContext](index: Ast.Expr[Ctx]): Ast.Expr[Ctx] = {
      index match {
        case e: Ast.Const[Ctx @unchecked] => e
        case Ast.Binop(op: ArithOperator, Ast.Const(Val.U256(l)), Ast.Const(Val.U256(r))) =>
          op match {
            case ArithOperator.Add =>
              Ast.Const(Val.U256(l.add(r).getOrElse(throw Error(s"Invalid array index ${index}"))))
            case ArithOperator.Sub =>
              Ast.Const(Val.U256(l.sub(r).getOrElse(throw Error(s"Invalid array index ${index}"))))
            case ArithOperator.Mul =>
              Ast.Const(Val.U256(l.mul(r).getOrElse(throw Error(s"Invalid array index ${index}"))))
            case ArithOperator.Div =>
              Ast.Const(Val.U256(l.div(r).getOrElse(throw Error(s"Invalid array index ${index}"))))
            case ArithOperator.Mod =>
              Ast.Const(Val.U256(l.mod(r).getOrElse(throw Error(s"Invalid array index ${index}"))))
            case ArithOperator.ModAdd => Ast.Const(Val.U256(l.modAdd(r)))
            case ArithOperator.ModSub => Ast.Const(Val.U256(l.modSub(r)))
            case ArithOperator.ModMul => Ast.Const(Val.U256(l.modMul(r)))
            case ArithOperator.SHL    => Ast.Const(Val.U256(l.shl(r)))
            case ArithOperator.SHR    => Ast.Const(Val.U256(l.shr(r)))
            case ArithOperator.BitAnd => Ast.Const(Val.U256(l.bitAnd(r)))
            case ArithOperator.BitOr  => Ast.Const(Val.U256(l.bitOr(r)))
            case ArithOperator.Xor    => Ast.Const(Val.U256(l.xor(r)))
            case _ =>
              throw new RuntimeException("Dead branch") // https://github.com/scala/bug/issues/9677
          }
        case e @ Ast.Binop(op, left, right) =>
          val expr = Ast.Binop(op, getConstantIndex(left), getConstantIndex(right))
          if (expr == e) expr else getConstantIndex(expr)
        case _ => index
      }
    }
    // scalastyle:on cyclomatic.complexity

    def checkConstantIndex(index: Int): Unit = {
      if (index < 0 || index >= maxVarIndex) {
        throw Error(s"Invalid array index $index")
      }
    }

    def getAndCheckConstantIndex[Ctx <: StatelessContext](index: Ast.Expr[Ctx]): Option[Int] = {
      getConstantIndex(index) match {
        case Ast.Const(Val.U256(v)) =>
          val idx = v.toInt.getOrElse(throw Compiler.Error(s"Invalid array index: $v"))
          checkConstantIndex(idx)
          Some(idx)
        case _ => None
      }
    }

    def buildFor(script: Ast.AssetScript): State[StatelessContext] =
      StateForScript(
        script.ident,
        mutable.HashMap.empty,
        Ast.FuncId.empty,
        0,
        script.funcTable,
        immutable.Map(script.ident -> ContractInfo(ContractKind.TxScript, script.funcTable))
      )

    @SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
    def buildFor(
        multiContract: MultiContract,
        contractIndex: Int
    ): State[StatefulContext] = {
      val contractsTable = multiContract.contracts.map { contract =>
        val kind = contract match {
          case _: Ast.ContractInterface =>
            ContractKind.Interface
          case _: Ast.TxScript =>
            ContractKind.TxScript
          case txContract: Ast.Contract =>
            ContractKind.Contract(txContract.isAbstract)
        }
        contract.ident -> ContractInfo(kind, contract.funcTable)
      }.toMap
      val contract = multiContract.get(contractIndex)
      StateForContract(
        contract.ident,
        contract.isInstanceOf[Ast.TxScript],
        mutable.HashMap.empty,
        Ast.FuncId.empty,
        0,
        contract.funcTable,
        contract.eventsInfo(),
        contractsTable
      )
    }
  }

  sealed trait ContractKind extends Serializable with Product {
    def instantiable: Boolean
    def inheritable: Boolean

    override def toString(): String = productPrefix
  }
  object ContractKind {
    case object TxScript extends ContractKind {
      def instantiable: Boolean = false
      def inheritable: Boolean  = false
    }
    case object Interface extends ContractKind {
      def instantiable: Boolean = true
      def inheritable: Boolean  = true
    }
    final case class Contract(isAbstract: Boolean) extends ContractKind {
      def instantiable: Boolean = !isAbstract
      def inheritable: Boolean  = isAbstract

      override def toString(): String = {
        if (isAbstract) "Abstract Contract" else "Contract"
      }
    }
  }

  final case class ContractInfo[Ctx <: StatelessContext](
      kind: ContractKind,
      funcs: immutable.Map[Ast.FuncId, ContractFunc[Ctx]]
  )

  trait CallGraph {
    def scope: Ast.FuncId

    // caller -> callees
    val internalCalls = mutable.HashMap.empty[Ast.FuncId, mutable.Set[Ast.FuncId]]
    def addInternalCall(callee: Ast.FuncId): Unit = {
      internalCalls.get(scope) match {
        case Some(callees) => callees += callee
        case None          => internalCalls.update(scope, mutable.Set(callee))
      }
    }
    // callee -> callers
    lazy val internalCallsReversed: mutable.Map[Ast.FuncId, mutable.ArrayBuffer[Ast.FuncId]] = {
      val reversed = mutable.Map.empty[Ast.FuncId, mutable.ArrayBuffer[Ast.FuncId]]
      internalCalls.foreach { case (caller, callees) =>
        callees.foreach { callee =>
          reversed.get(callee) match {
            case None          => reversed.update(callee, mutable.ArrayBuffer(caller))
            case Some(callers) => callers.addOne(caller)
          }
        }
      }
      reversed
    }

    val externalCalls = mutable.HashMap.empty[Ast.FuncId, mutable.Set[(Ast.TypeId, Ast.FuncId)]]
    def addExternalCall(contract: Ast.TypeId, func: Ast.FuncId): Unit = {
      val funcRef = contract -> func
      externalCalls.get(scope) match {
        case Some(callees) => callees += funcRef
        case None          => externalCalls.update(scope, mutable.Set(funcRef))
      }
    }
  }

  // scalastyle:off number.of.methods
  sealed trait State[Ctx <: StatelessContext] extends CallGraph {
    def typeId: Ast.TypeId
    def varTable: mutable.HashMap[String, VarInfo]
    var scope: Ast.FuncId
    var varIndex: Int
    def funcIdents: immutable.Map[Ast.FuncId, ContractFunc[Ctx]]
    def contractTable: immutable.Map[Ast.TypeId, ContractInfo[Ctx]]
    private var freshNameIndex: Int              = 0
    private var arrayIndexVar: Option[Ast.Ident] = None
    val usedVars: mutable.Set[String]            = mutable.Set.empty[String]
    val warnings: mutable.ArrayBuffer[String]    = mutable.ArrayBuffer.empty[String]
    def getWarnings: AVector[String]             = AVector.from(warnings)
    def eventsInfo: Seq[EventInfo]

    @inline final def freshName(): String = {
      val name = s"_generated#$freshNameIndex"
      freshNameIndex += 1
      name
    }

    def getArrayIndexVar(): Ast.Ident = {
      arrayIndexVar match {
        case Some(ident) => ident
        case None =>
          val ident = Ast.Ident(freshName())
          addLocalVariable(ident, Type.U256, isMutable = true, isUnused = false, isGenerated = true)
          arrayIndexVar = Some(ident)
          ident
      }
    }

    @SuppressWarnings(
      Array("org.wartremover.warts.AsInstanceOf", "org.wartremover.warts.Recursion")
    )
    def getOrCreateArrayRef(
        expr: Ast.Expr[Ctx]
    ): (ArrayTransformer.ArrayRef[Ctx], Seq[Instr[Ctx]]) = {
      expr match {
        case Ast.ArrayElement(array, indexes) =>
          val (arrayRef, codes) = getOrCreateArrayRef(array)
          val subArrayRef       = arrayRef.subArray(this, indexes)
          (subArrayRef, codes)
        case Ast.Variable(ident)  => (getArrayRef(ident), Seq.empty)
        case Ast.ParenExpr(inner) => getOrCreateArrayRef(inner)
        case _ =>
          val arrayType = expr.getType(this)(0).asInstanceOf[Type.FixedSizeArray]
          val arrayRef =
            ArrayTransformer.init(
              this,
              arrayType,
              freshName(),
              isMutable = false,
              isUnused = false,
              isLocal = true,
              isGenerated = true,
              VarInfo.Local
            )
          val codes = expr.genCode(this) ++ arrayRef.genStoreCode(this).reverse.flatten
          (arrayRef, codes)
      }
    }

    def addArrayRef(
        ident: Ast.Ident,
        isMutable: Boolean,
        isUnused: Boolean,
        isGenerated: Boolean,
        arrayRef: ArrayTransformer.ArrayRef[Ctx]
    ): Unit = {
      val sname = checkNewVariable(ident)
      varTable(sname) = VarInfo.ArrayRef(isMutable, isUnused, isGenerated, arrayRef)
    }

    def getArrayRef(ident: Ast.Ident): ArrayTransformer.ArrayRef[Ctx] = {
      getVariable(ident) match {
        case info: VarInfo.ArrayRef[Ctx @unchecked] => info.ref
        case _                                      => throw Error(s"Array $ident does not exist")
      }
    }

    def setFuncScope(funcId: Ast.FuncId): Unit = {
      scope = funcId
      varIndex = 0
      arrayIndexVar = None
    }

    protected def scopedName(name: String): String = {
      if (scope == Ast.FuncId.empty) name else s"${scope.name}.$name"
    }

    def addTemplateVariable(ident: Ast.Ident, tpe: Type, index: Int): Unit = {
      val sname = checkNewVariable(ident)
      tpe match {
        case _: Type.FixedSizeArray =>
          throw Error("Template variable does not support Array yet")
        case c: Type.Contract =>
          val varType = Type.Contract.local(c.id, ident)
          varTable(sname) = VarInfo.Template(varType, index)
        case _ =>
          varTable(sname) = VarInfo.Template(tpe, index)
      }
    }
    def addFieldVariable(
        ident: Ast.Ident,
        tpe: Type,
        isMutable: Boolean,
        isUnused: Boolean,
        isGenerated: Boolean
    ): Unit = {
      addVariable(ident, tpe, isMutable, isUnused, isLocal = false, isGenerated, VarInfo.Field)
    }
    def addLocalVariable(
        ident: Ast.Ident,
        tpe: Type,
        isMutable: Boolean,
        isUnused: Boolean,
        isGenerated: Boolean
    ): Unit = {
      addVariable(ident, tpe, isMutable, isUnused, isLocal = true, isGenerated, VarInfo.Local)
    }
    def addVariable(
        ident: Ast.Ident,
        tpe: Type,
        isMutable: Boolean,
        isUnused: Boolean,
        isLocal: Boolean,
        isGenerated: Boolean,
        varInfoBuilder: Compiler.VarInfoBuilder
    ): Unit = {
      val sname = checkNewVariable(ident)
      tpe match {
        case t: Type.FixedSizeArray =>
          ArrayTransformer.init(
            this,
            t,
            ident.name,
            isMutable,
            isUnused,
            isLocal,
            isGenerated,
            varInfoBuilder
          )
          ()
        case c: Type.Contract =>
          val varType = Type.Contract.local(c.id, ident)
          varTable(sname) =
            varInfoBuilder(varType, isMutable, isUnused, varIndex.toByte, isGenerated)
          varIndex += 1
        case _ =>
          varTable(sname) = varInfoBuilder(tpe, isMutable, isUnused, varIndex.toByte, isGenerated)
          varIndex += 1
      }
    }
    def addConstantVariable(ident: Ast.Ident, tpe: Type, instrs: Seq[Instr[Ctx]]): Unit = {
      val sname = checkNewVariable(ident)
      varTable(sname) = VarInfo.Constant(tpe, instrs)
    }

    private def checkNewVariable(ident: Ast.Ident): String = {
      val name  = ident.name
      val sname = scopedName(name)
      if (varTable.contains(name)) {
        throw Error(s"Global variable has the same name as local variable: $name")
      } else if (varTable.contains(sname)) {
        throw Error(s"Local variables have the same name: $name")
      } else if (varIndex >= State.maxVarIndex) {
        throw Error(s"Number of variables more than ${State.maxVarIndex}")
      }
      sname
    }

    def getVariable(ident: Ast.Ident): VarInfo = {
      val name  = ident.name
      val sname = scopedName(ident.name)
      val (varName, varInfo) = varTable.get(sname) match {
        case Some(varInfo) => (sname, varInfo)
        case None =>
          varTable.get(name) match {
            case Some(varInfo) => (name, varInfo)
            case None          => throw Error(s"Variable $sname does not exist")
          }
      }
      usedVars.add(varName)
      varInfo
    }

    def addUsedVars(names: Set[String]): Unit = usedVars.addAll(names)

    def checkUnusedLocalVars(funcId: Ast.FuncId): Unit = {
      val prefix = s"${funcId.name}."
      val unusedVars = varTable.filter { case (name, varInfo) =>
        name.startsWith(prefix) &&
        !usedVars.contains(name) &&
        !varInfo.isGenerated &&
        !varInfo.isUnused
      }
      if (unusedVars.nonEmpty) {
        val unusedVarsString = unusedVars.keys.toArray.sorted.mkString(", ")
        warnings += s"Found unused variables in ${typeId.name}: ${unusedVarsString}"
      }
      usedVars.filterInPlace(name => !name.startsWith(prefix))
    }

    def checkUnusedFields(): Unit = {
      val unusedVars = varTable.filter { case (name, varInfo) =>
        !usedVars.contains(name) && !varInfo.isGenerated && !varInfo.isUnused
      }
      val unusedConstants = mutable.ArrayBuffer.empty[String]
      val unusedFields    = mutable.ArrayBuffer.empty[String]
      unusedVars.foreach {
        case (name, _: VarInfo.Constant[_])      => unusedConstants.addOne(name)
        case (name, varInfo) if !varInfo.isLocal => unusedFields.addOne(name)
        case _                                   => ()
      }
      if (unusedConstants.nonEmpty) {
        warnings += s"Found unused constants in ${typeId.name}: ${unusedConstants.sorted.mkString(", ")}"
      }
      if (unusedFields.nonEmpty) {
        warnings += s"Found unused fields in ${typeId.name}: ${unusedFields.sorted.mkString(", ")}"
      }
    }

    def getLocalVars(func: Ast.FuncId): Seq[VarInfo] = {
      varTable.view
        .filterKeys(_.startsWith(func.name))
        .values
        .filter(_.isInstanceOf[VarInfo.Local])
        .map(_.asInstanceOf[VarInfo.Local])
        .toSeq
        .sortBy(_.index)
    }

    def checkArrayIndexType(index: Ast.Expr[Ctx]): Unit = {
      index.getType(this) match {
        case Seq(Type.U256) =>
        case tpe            => throw Compiler.Error(s"Invalid array index type $tpe")
      }
    }

    @scala.annotation.tailrec
    private def arrayElementType(
        arrayType: Type.FixedSizeArray,
        indexes: Seq[Ast.Expr[Ctx]]
    ): Type = {
      if (indexes.length == 1) {
        arrayType.baseType
      } else {
        arrayType.baseType match {
          case baseType: Type.FixedSizeArray => arrayElementType(baseType, indexes.drop(1))
          case tpe => throw Compiler.Error(s"Expect array type, have: $tpe")
        }
      }
    }

    def getArrayElementType(array: Ast.Expr[Ctx], indexes: Seq[Ast.Expr[Ctx]]): Type = {
      getArrayElementType(array.getType(this), indexes)
    }
    def getArrayElementType(tpes: Seq[Type], indexes: Seq[Ast.Expr[Ctx]]): Type = {
      indexes.foreach(checkArrayIndexType)
      tpes match {
        case Seq(tpe: Type.FixedSizeArray) => arrayElementType(tpe, indexes)
        case tpe =>
          throw Compiler.Error(s"Expect array type, have: $tpe")
      }
    }

    def genLoadCode(ident: Ast.Ident): Seq[Instr[Ctx]]

    def genLoadCode(offset: ArrayTransformer.ArrayVarOffset[Ctx], isLocal: Boolean): Seq[Instr[Ctx]]

    def genStoreCode(ident: Ast.Ident): Seq[Seq[Instr[Ctx]]]

    def genStoreCode(
        offset: ArrayTransformer.ArrayVarOffset[Ctx],
        isLocal: Boolean
    ): Seq[Instr[Ctx]]

    def getType(ident: Ast.Ident): Type = getVariable(ident).tpe

    def getFunc(call: Ast.FuncId): FuncInfo[Ctx] = {
      if (call.isBuiltIn) {
        getBuiltInFunc(call)
      } else {
        getNewFunc(call)
      }
    }

    def getContract(objId: Ast.Ident): Ast.TypeId = {
      getVariable(objId).tpe match {
        case c: Type.Contract => c.id
        case _                => throw Error(s"Invalid contract object id ${objId.name}")
      }
    }

    def getFunc(typeId: Ast.TypeId, callId: Ast.FuncId): ContractFunc[Ctx] = {
      getContractInfo(typeId).funcs
        .getOrElse(callId, throw Error(s"Function ${typeId}.${callId.name} does not exist"))
    }

    def getContractInfo(typeId: Ast.TypeId): ContractInfo[Ctx] = {
      contractTable.getOrElse(typeId, throw Error(s"Contract ${typeId.name} does not exist"))
    }

    def getEvent(typeId: Ast.TypeId): EventInfo = {
      eventsInfo
        .find(_.typeId == typeId)
        .getOrElse(
          throw Error(s"Event ${typeId.name} does not exist")
        )
    }

    protected def getBuiltInFunc(call: Ast.FuncId): FuncInfo[Ctx]

    private def getNewFunc(call: Ast.FuncId): FuncInfo[Ctx] = {
      funcIdents.getOrElse(call, throw Error(s"Function ${call.name} does not exist"))
    }

    def checkArguments(args: Seq[Ast.Argument]): Unit = {
      args.foreach(_.tpe match {
        case c: Type.Contract => checkContractType(c.id)
        case _                =>
      })
    }

    def checkContractType(typeId: Ast.TypeId): Unit = {
      if (!contractTable.contains(typeId)) {
        throw Error(s"Contract ${typeId.name} does not exist")
      }
    }

    def checkAssign(ident: Ast.Ident, tpe: Seq[Type]): Unit = {
      checkAssign(ident, expectOneType(ident, tpe))
    }

    def checkAssign(ident: Ast.Ident, tpe: Type): Unit = {
      val varInfo = getVariable(ident)
      if (varInfo.tpe != tpe) throw Error(s"Assign $tpe value to $ident: ${varInfo.tpe.toVal}")
      if (!varInfo.isMutable) throw Error(s"Assign value to immutable variable $ident")
    }

    def checkReturn(returnType: Seq[Type]): Unit = {
      val rtype = funcIdents(scope).returnType
      if (returnType != rtype) {
        throw Error(s"Invalid return types: expected $rtype, got $returnType")
      }
    }
  }
  // scalastyle:on number.of.methods

  type Contract[Ctx <: StatelessContext] = immutable.Map[Ast.FuncId, ContractFunc[Ctx]]
  final case class StateForScript(
      typeId: Ast.TypeId,
      varTable: mutable.HashMap[String, VarInfo],
      var scope: Ast.FuncId,
      var varIndex: Int,
      funcIdents: immutable.Map[Ast.FuncId, ContractFunc[StatelessContext]],
      contractTable: immutable.Map[Ast.TypeId, ContractInfo[StatelessContext]]
  ) extends State[StatelessContext] {
    override def eventsInfo: Seq[EventInfo] = Seq.empty

    protected def getBuiltInFunc(call: Ast.FuncId): FuncInfo[StatelessContext] = {
      BuiltIn.statelessFuncs
        .getOrElse(call.name, throw Error(s"Built-in function ${call.name} does not exist"))
    }

    private def genVarIndexCode(
        offset: ArrayTransformer.ArrayVarOffset[StatelessContext],
        isLocal: Boolean,
        constantIndex: Byte => Instr[StatelessContext],
        varIndex: Instr[StatelessContext]
    ): Seq[Instr[StatelessContext]] = {
      if (!isLocal) {
        throw Error(s"Script should not have fields")
      }

      offset match {
        case ArrayTransformer.ConstantArrayVarOffset(value) =>
          State.checkConstantIndex(value)
          Seq(constantIndex(value.toByte))
        case ArrayTransformer.VariableArrayVarOffset(instrs) =>
          instrs :+ varIndex
      }
    }

    def genLoadCode(
        offset: ArrayTransformer.ArrayVarOffset[StatelessContext],
        isLocal: Boolean
    ): Seq[Instr[StatelessContext]] =
      genVarIndexCode(offset, isLocal, LoadLocal.apply, LoadLocalByIndex)

    def genStoreCode(
        offset: ArrayTransformer.ArrayVarOffset[StatelessContext],
        isLocal: Boolean
    ): Seq[Instr[StatelessContext]] =
      genVarIndexCode(offset, isLocal, StoreLocal.apply, StoreLocalByIndex)

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def genLoadCode(ident: Ast.Ident): Seq[Instr[StatelessContext]] = {
      getVariable(ident) match {
        case _: VarInfo.Field    => throw Error("Script should not have fields")
        case v: VarInfo.Local    => Seq(LoadLocal(v.index))
        case v: VarInfo.Template => Seq(TemplateVariable(ident.name, v.tpe.toVal, v.index))
        case _: VarInfo.ArrayRef[StatelessContext @unchecked] =>
          getArrayRef(ident).genLoadCode(this)
        case v: VarInfo.Constant[StatelessContext @unchecked] => v.instrs
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def genStoreCode(ident: Ast.Ident): Seq[Seq[Instr[StatelessContext]]] = {
      getVariable(ident) match {
        case _: VarInfo.Field    => throw Error("Script should not have fields")
        case v: VarInfo.Local    => Seq(Seq(StoreLocal(v.index)))
        case _: VarInfo.Template => throw Error(s"Unexpected template variable: ${ident.name}")
        case ref: VarInfo.ArrayRef[StatelessContext @unchecked] => ref.ref.genStoreCode(this)
        case _: VarInfo.Constant[StatelessContext @unchecked] =>
          throw Error(s"Unexpected constant variable: ${ident.name}")
      }
    }
  }

  final case class StateForContract(
      typeId: Ast.TypeId,
      isTxScript: Boolean,
      varTable: mutable.HashMap[String, VarInfo],
      var scope: Ast.FuncId,
      var varIndex: Int,
      funcIdents: immutable.Map[Ast.FuncId, ContractFunc[StatefulContext]],
      eventsInfo: Seq[EventInfo],
      contractTable: immutable.Map[Ast.TypeId, ContractInfo[StatefulContext]]
  ) extends State[StatefulContext] {
    protected def getBuiltInFunc(call: Ast.FuncId): FuncInfo[StatefulContext] = {
      BuiltIn.statefulFuncs
        .getOrElse(call.name, throw Error(s"Built-in function ${call.name} does not exist"))
    }

    private def genVarIndexCode(
        offset: ArrayTransformer.ArrayVarOffset[StatefulContext],
        isLocal: Boolean,
        localConstantIndex: Byte => Instr[StatefulContext],
        fieldConstantIndex: Byte => Instr[StatefulContext],
        localVarIndex: Instr[StatefulContext],
        fieldVarIndex: Instr[StatefulContext]
    ): Seq[Instr[StatefulContext]] = {
      offset match {
        case ArrayTransformer.ConstantArrayVarOffset(value) =>
          State.checkConstantIndex(value)
          val index = value.toByte
          if (isLocal) Seq(localConstantIndex(index)) else Seq(fieldConstantIndex(index))
        case ArrayTransformer.VariableArrayVarOffset(instrs) =>
          val instr = if (isLocal) localVarIndex else fieldVarIndex
          instrs :+ instr
      }
    }

    def genLoadCode(
        offset: ArrayTransformer.ArrayVarOffset[StatefulContext],
        isLocal: Boolean
    ): Seq[Instr[StatefulContext]] =
      genVarIndexCode(
        offset,
        isLocal,
        LoadLocal.apply,
        LoadField.apply,
        LoadLocalByIndex,
        LoadFieldByIndex
      )

    def genStoreCode(
        offset: ArrayTransformer.ArrayVarOffset[StatefulContext],
        isLocal: Boolean
    ): Seq[Instr[StatefulContext]] =
      genVarIndexCode(
        offset,
        isLocal,
        StoreLocal.apply,
        StoreField.apply,
        StoreLocalByIndex,
        StoreFieldByIndex
      )

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def genLoadCode(ident: Ast.Ident): Seq[Instr[StatefulContext]] = {
      val varInfo = getVariable(ident)
      getVariable(ident) match {
        case v: VarInfo.Field    => Seq(LoadField(v.index))
        case v: VarInfo.Local    => Seq(LoadLocal(v.index))
        case v: VarInfo.Template => Seq(TemplateVariable(ident.name, varInfo.tpe.toVal, v.index))
        case _: VarInfo.ArrayRef[StatefulContext @unchecked] => getArrayRef(ident).genLoadCode(this)
        case v: VarInfo.Constant[StatefulContext @unchecked] => v.instrs
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    def genStoreCode(ident: Ast.Ident): Seq[Seq[Instr[StatefulContext]]] = {
      getVariable(ident) match {
        case v: VarInfo.Field    => Seq(Seq(StoreField(v.index)))
        case v: VarInfo.Local    => Seq(Seq(StoreLocal(v.index)))
        case _: VarInfo.Template => throw Error(s"Unexpected template variable: ${ident.name}")
        case ref: VarInfo.ArrayRef[StatefulContext @unchecked] => ref.ref.genStoreCode(this)
        case _: VarInfo.Constant[StatefulContext @unchecked] =>
          throw Error(s"Unexpected constant variable: ${ident.name}")
      }
    }
  }

  def genLogs(logFieldLength: Int): LogInstr = {
    if (logFieldLength >= 0 && logFieldLength < Instr.allLogInstrs.length) {
      Instr.allLogInstrs(logFieldLength)
    } else {
      throw Compiler.Error(s"Max 8 fields allowed for contract events")
    }
  }
}

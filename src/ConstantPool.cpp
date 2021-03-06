/*
 * ConstantPool.cpp
 *
 *  Created on: Sep 15, 2010
 *      Author: joe
 *
 *  Copyright 2010 Joseph Kulig. All rights reserved.
 */

#include <stdio.h>
#include <iostream>

#include "ConstantPool.h"
#include "ClassFile.h"

ConstantPool::ConstantPool() {

}

CPUtf8Info::CPUtf8Info(ClassFile *file) {
	uint8_t *data = file->getFilePtr();

	tag = CONSTANT_Utf8;
	length = be16toh(*(uint16_t *)data);
	aString.assign((const char *)(data+2), length);
	file->setFilePtr(data+length+2);
//	cout << "utf8: " << aString << endl;
}

CPIntegerInfo::CPIntegerInfo(ClassFile *file) {
	uint8_t *data = file->getFilePtr();

	tag = CONSTANT_Integer;
	bytes = be32toh(*(uint32_t *)data);
	file->setFilePtr(data+4);
}

CPFloatInfo::CPFloatInfo(ClassFile *file) {
	uint8_t *data = file->getFilePtr();

	tag = CONSTANT_Float;
	bytes = be32toh(*(uint32_t *)data);
	file->setFilePtr(data+4);
}

CPClassInfo::CPClassInfo(ClassFile *file) {
	uint8_t *data = file->getFilePtr();

	tag = CONSTANT_Class;
	nameIndex = be16toh(*(uint16_t *)data);
	file->setFilePtr(data+2);
}

CPFieldref::CPFieldref(ClassFile *file) {
	uint8_t *data = file->getFilePtr();

	tag = CONSTANT_Fieldref;
	classIndex = be16toh(*(uint16_t *)data);
	nameTypeIndex = be16toh(*(uint16_t *)(data+2));
	file->setFilePtr(data+4);
}

CPMethodref::CPMethodref(ClassFile *file) {
	uint8_t *data = file->getFilePtr();

	classIndex = be16toh(*(uint16_t *)data);
	nameTypeIndex = be16toh(*(uint16_t *)(data+2));
	file->setFilePtr(data+4);
}

CPInterfaceref::CPInterfaceref(ClassFile *file) {
	uint8_t *data = file->getFilePtr();

	classIndex = be16toh(*(uint16_t *)data);
	nameTypeIndex = be16toh(*(uint16_t *)(data+2));
	file->setFilePtr(data+4);
}

CPStringInfo::CPStringInfo(ClassFile *file) {
	uint8_t *data = file->getFilePtr();

	stringIndex = be16toh(*(uint16_t *)data);
	file->setFilePtr(data+2);
}

CPLongInfo::CPLongInfo(ClassFile *file) {
	uint8_t *data = file->getFilePtr();

	bytes = be64toh(*(uint64_t *)data);
	file->setFilePtr(data+8);
}

CPDoubleInfo::CPDoubleInfo(ClassFile *file) {
	uint8_t *data = file->getFilePtr();

	bytes = be64toh(*(uint64_t *)data);
	file->setFilePtr(data+8);
}

CPNameTypeInfo::CPNameTypeInfo(ClassFile *file) {
	uint8_t *data = file->getFilePtr();

	nameIndex = be16toh(*(uint16_t *)data);
	descriptorIndex = be16toh(*(uint16_t *)(data+2));
	file->setFilePtr(data+4);
}

/**
 * Add constants from classFile. classFile object's file ptr must be pointing to
 * the class file constant pool. Also the constant_pool_count must be set.
 */
void ConstantPool::add(ClassFile *classFile) {
	uint8_t tag;
	uint8_t *fileData;
	ConstantPool *cp;
	int doubleEntry;
	int i;

	constants.resize(classFile->constantPoolCount());
	for (i = 1; i < classFile->constantPoolCount(); i++) {
		fileData = classFile->getFilePtr();
		tag = *fileData;
		classFile->setFilePtr(fileData + 1);
		// only longs and double take up two slots
		doubleEntry = 0;
		switch (tag) {
		case CONSTANT_Utf8:
			cp = new CPUtf8Info(classFile);
			break;
		case CONSTANT_Integer:
			cp = new CPIntegerInfo(classFile);
			break;
		case CONSTANT_Float:
			cp = new CPFloatInfo(classFile);
			break;
		case CONSTANT_Class:
			cp = new CPClassInfo(classFile);
			break;
		case CONSTANT_Fieldref:
			cp = new CPFieldref(classFile);
			break;
		case CONSTANT_Methodref:
			cp = new CPMethodref(classFile);
			break;
		case CONSTANT_InterfaceMethodref:
			cp = new CPInterfaceref(classFile);
			break;
		case CONSTANT_String:
			cp = new CPStringInfo(classFile);
			break;
		case CONSTANT_Long:
			cp = new CPLongInfo(classFile);
			doubleEntry = 1;
			break;
		case CONSTANT_Double:
			cp = new CPDoubleInfo(classFile);
			doubleEntry = 1;
			break;
		case CONSTANT_NameAndType:
			cp = new CPNameTypeInfo(classFile);
			break;
		default:
			printf("cp unknown tag: %d i=%d\n", tag, i);
			throw(-2);
		}
		cp->tag = tag;
		constants[i] = cp;
		// TODO: set constant pool entry to empty
		i += doubleEntry;
	}
}

void ConstantPool::verifyClassIndex(uint16_t index)
{
    if(constants[index]->tag != CONSTANT_Utf8) {
		cout << ": bad class name index! " << index << " " <<  constants[index]->tag;
		throw(-3);
	}
}

ConstantPool * ConstantPool::getConstant(uint16_t index) {
	return constants[index];
}

bool ConstantPool::isClassref() {
	return tag == CONSTANT_Class;
}

bool ConstantPool::isFieldref() {
	return tag == CONSTANT_Fieldref;
}

bool ConstantPool::isMethodref() {
	return tag == CONSTANT_Methodref;
}

bool ConstantPool::isInterfaceref() {
	return tag == CONSTANT_InterfaceMethodref;
}

bool ConstantPool::isString() {
	return tag == CONSTANT_String;
}

bool ConstantPool::isInteger() {
	return tag == CONSTANT_Integer;
}

bool ConstantPool::isFloat() {
	return tag == CONSTANT_Float;
}

bool ConstantPool::isLong() {
	return tag == CONSTANT_Long;
}

bool ConstantPool::isDouble() {
	return tag == CONSTANT_Double;
}

bool ConstantPool::isNameType() {
	return tag == CONSTANT_NameAndType;
}

bool ConstantPool::isUtf8() {
	return tag == CONSTANT_Utf8;
}

string& CPUtf8Info::name() {
	return aString;
}

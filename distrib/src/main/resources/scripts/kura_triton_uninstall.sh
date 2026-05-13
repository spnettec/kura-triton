#!/bin/bash
#
# Copyright (c) 2026 YOFC IOT and others
#
# This program and the accompanying materials are made
# available under the terms of the Eclipse Public License 2.0
# which is available at https://www.eclipse.org/legal/epl-2.0/
#
# SPDX-License-Identifier: EPL-2.0
#
# Contributors:
#  YOFC IOT
#

STATUS=$1
BASE_DIR=$2
KURA_SYMLINK=$3

SIBLING_NAME="triton"
REGISTRY="${BASE_DIR}/${KURA_SYMLINK}/framework/sibling-install-order"

echo "Uninstalling Kura Triton addon..."
echo "  Status: ${STATUS}"
echo "  Base directory: ${BASE_DIR}"
echo "  Kura symlink: ${KURA_SYMLINK}"

# Remove this sibling from the install-order registry
if [ -f "${REGISTRY}" ]; then
    echo "  Removing ${SIBLING_NAME} from sibling-install-order"
    TMP=$(mktemp 2>/dev/null || echo "${REGISTRY}.tmp.$$")
    grep -vFx "${SIBLING_NAME}" "${REGISTRY}" > "${TMP}" 2>/dev/null || true
    mv -f "${TMP}" "${REGISTRY}" 2>/dev/null || rm -f "${TMP}"
else
    echo "  Warning: sibling-install-order registry not found"
fi

echo "Kura Triton addon uninstallation completed."

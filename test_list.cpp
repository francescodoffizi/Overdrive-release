#include <list>
#include <iostream>
#include <cstdint>
int main() {
    std::list<uint32_t> l;
    l.push_back(0x11111111);
    l.push_back(0x22222222);
    
    void** list_base = (void**)&l;
    void** first_node = (void**)list_base[0];
    void** last_node = (void**)list_base[1];
    
    std::cout << "List Base: " << list_base << std::endl;
    std::cout << "list[0] = " << list_base[0] << " (first_node)" << std::endl;
    std::cout << "list[1] = " << list_base[1] << " (last_node)" << std::endl;
    
    std::cout << "first_node[0] = " << first_node[0] << " (next of first)" << std::endl;
    std::cout << "first_node[1] = " << first_node[1] << " (prev of first)" << std::endl;
    
    uint32_t* val1 = (uint32_t*)((char*)first_node + 16);
    uint32_t* val2 = (uint32_t*)((char*)last_node + 16);
    std::cout << "first_node val: 0x" << std::hex << *val1 << std::endl;
    std::cout << "last_node val: 0x" << std::hex << *val2 << std::endl;
    
    return 0;
}
